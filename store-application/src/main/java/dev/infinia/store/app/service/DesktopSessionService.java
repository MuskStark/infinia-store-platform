package dev.infinia.store.app.service;

import dev.infinia.store.app.security.LocalTokenService;
import dev.infinia.store.domain.model.StoreUser;
import dev.infinia.store.domain.port.IdentityRepositories;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Rotating per-install refresh credentials for the FengYu desktop client
 * (design §7.2). The public OAuth client receives no refresh token from the
 * authorization server (SAS hard-gates them away from public clients), so a
 * long-lived desktop session rides this store-managed credential instead:
 *
 * <ul>
 *   <li><b>Issue</b> — right after the PKCE code exchange, the client presents
 *       its fresh access token and receives one opaque 256-bit credential bound
 *       to the session ledger row. Only its SHA-256 hash is persisted.</li>
 *   <li><b>Refresh</b> — the credential alone authenticates the call: it is
 *       single-use (atomic consume), rotated on every refresh, sliding-TTL with
 *       an absolute family cap, and the new access token keeps the session's
 *       {@code sid} so the ledger revocation in the resource server applies.</li>
 *   <li><b>Replay</b> — presenting an already-consumed credential revokes the
 *       whole family and the session: theft ends in detection, not silent
 *       sharing.</li>
 * </ul>
 *
 * <p>Failures are uniformly "invalid credential" (an empty Optional) — callers
 * cannot distinguish unknown, expired, revoked and replayed tokens. The methods
 * deliberately never throw on an invalid credential, so the replay-revocation
 * side effects commit instead of rolling back with an exception.
 */
@Service
public class DesktopSessionService {

    public record IssuedCredential(String refreshToken, Instant expiresAt) {}

    public record RefreshedGrant(String accessToken, long expiresIn, String refreshToken) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IdentityRepositories.RefreshTokenRepository refreshTokens;
    private final IdentityRepositories.SessionRepository sessions;
    private final IdentityRepositories.UserRepository users;
    private final LocalTokenService tokens;
    private final AuditService audit;
    private final Duration slidingTtl;
    private final Duration absoluteTtl;

    public DesktopSessionService(
            IdentityRepositories.RefreshTokenRepository refreshTokens,
            IdentityRepositories.SessionRepository sessions,
            IdentityRepositories.UserRepository users,
            LocalTokenService tokens,
            AuditService audit,
            @Value("${store.refresh.sliding-ttl-days:30}") long slidingTtlDays,
            @Value("${store.refresh.absolute-ttl-days:90}") long absoluteTtlDays) {
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.users = users;
        this.tokens = tokens;
        this.audit = audit;
        this.slidingTtl = Duration.ofDays(Math.max(1, slidingTtlDays));
        this.absoluteTtl = Duration.ofDays(Math.max(slidingTtl.toDays(), absoluteTtlDays));
    }

    /**
     * Issues the session's rotating credential: exactly one stays active per
     * session — issuing a new one kills any previous one. The absolute family
     * deadline is carried across re-issues so it can never be reset.
     */
    @Transactional
    public IssuedCredential issue(IdentityRepositories.UserSessionRecord session,
            StoreUser user) {
        Instant now = Instant.now();
        refreshTokens.revokeFamily(session.id());
        Instant absoluteDeadline = refreshTokens.findBySessionId(session.id()).stream()
                .map(IdentityRepositories.RefreshTokenRecord::absoluteDeadline)
                .min(Instant::compareTo)
                .orElse(now.plus(absoluteTtl));
        String token = randomToken();
        Instant expiresAt = now.plus(slidingTtl);
        refreshTokens.save(new IdentityRepositories.RefreshTokenRecord(
                sha256(token), session.id(), user.id, session.clientId(), now, expiresAt,
                absoluteDeadline, null, false));
        audit.record("USER", user.id.toString(), "auth.desktop_session", "SESSION",
                session.id().toString(), null, session.clientId(), null);
        return new IssuedCredential(token, expiresAt);
    }

    /**
     * Single-use refresh with rotation; an empty Optional is the uniform
     * invalid-credential answer. A consumed credential presented again revokes
     * the family and the session (reuse detection); a lost consume race is
     * treated identically.
     */
    @Transactional
    public Optional<RefreshedGrant> refresh(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256(presentedToken.trim());
        Optional<IdentityRepositories.RefreshTokenRecord> found =
                refreshTokens.findByTokenHash(hash);
        if (found.isEmpty() || found.get().revoked()) {
            return Optional.empty();
        }
        IdentityRepositories.RefreshTokenRecord row = found.get();
        if (row.consumedAt() != null) {
            // Replay: the credential was already rotated — someone other than
            // the holder is presenting it. Kill the family and the session.
            replayed(row, "consumed");
            return Optional.empty();
        }
        Optional<IdentityRepositories.UserSessionRecord> session =
                sessions.findById(row.sessionId());
        if (session.isEmpty() || session.get().revoked()) {
            refreshTokens.revokeFamily(row.sessionId());
            return Optional.empty();
        }
        StoreUser user = users.findById(row.userId()).orElse(null);
        if (user == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (now.isAfter(row.expiresAt()) || now.isAfter(row.absoluteDeadline())) {
            refreshTokens.revokeFamily(row.sessionId());
            return Optional.empty();
        }
        if (refreshTokens.consume(hash, now) == 0) {
            // Another thread consumed it microseconds ago — that is a replay.
            replayed(row, "race");
            return Optional.empty();
        }
        String next = randomToken();
        refreshTokens.save(new IdentityRepositories.RefreshTokenRecord(
                sha256(next), row.sessionId(), row.userId(), row.clientId(), now,
                now.plus(slidingTtl), row.absoluteDeadline(), null, false));
        IdentityRepositories.UserSessionRecord active = session.get();
        sessions.save(new IdentityRepositories.UserSessionRecord(active.id(),
                active.userId(), active.clientId(), active.kind(), active.deviceId(),
                active.createdAt(), now, active.revoked(), active.remoteIpHash()));
        audit.record("USER", row.userId().toString(), "auth.refresh", "SESSION",
                row.sessionId().toString(), null, "rotated", null);
        return Optional.of(new RefreshedGrant(tokens.mintForSession(user, row.sessionId()),
                tokens.accessTtlSeconds(), next));
    }

    /**
     * Sign-out revocation. Deliberately uniform: always appears to succeed so
     * the endpoint never confirms whether a credential exists.
     */
    @Transactional
    public void revoke(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(sha256(presentedToken.trim()))
                .ifPresent(row -> revokeFamilyAndSession(row.sessionId(), row.userId()));
    }

    private void replayed(IdentityRepositories.RefreshTokenRecord row, String how) {
        revokeFamilyAndSession(row.sessionId(), row.userId());
        audit.record("USER", row.userId().toString(), "auth.refresh_replay", "SESSION",
                row.sessionId().toString(), null, how, null);
    }

    private void revokeFamilyAndSession(UUID sessionId, UUID userId) {
        refreshTokens.revokeFamily(sessionId);
        sessions.findById(sessionId)
                .filter(s -> !s.revoked())
                .ifPresent(s -> sessions.markRevoked(sessionId));
        audit.record("USER", userId.toString(), "auth.refresh_revoked", "SESSION",
                sessionId.toString(), null, null, null);
    }

    /** 256-bit URL-safe random token (43 chars) — UUIDs only carry ~122 bits. */
    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
