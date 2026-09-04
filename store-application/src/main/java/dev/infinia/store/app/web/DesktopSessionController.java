package dev.infinia.store.app.web;

import dev.infinia.store.app.service.AccountService;
import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.DesktopSessionService;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.contract.error.StoreErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Desktop long-lived sessions (design §7.2): the public OAuth client gets a
 * store-managed rotating refresh credential instead of an authorization-server
 * refresh token. The credential itself is the only authenticator on refresh —
 * no client secret pairing, so a store upgrade can never break client sign-in.
 *
 * <p>All failures are a uniform 401 {@code invalid_grant} (unknown, expired,
 * revoked and replayed credentials are indistinguishable), responses carry
 * {@code Cache-Control: no-store}, and the unauthenticated endpoints are
 * rate-limited per client address.
 */
@RestController
@RequestMapping("/api/v1/auth")
class DesktopSessionController {

    private final DesktopSessionService desktopSessions;
    private final AccountService accounts;
    private final CurrentPrincipal principal;

    /**
     * One small fixed-window limiter for the two endpoints whose only
     * authenticator is the presented credential — 256-bit tokens make brute
     * force infeasible, this just stops hammering the ledger lookup.
     */
    private final int rateLimitPerMinute;
    private record Window(long minute, int count) {}
    private final ConcurrentHashMap<String, Window> rate = new ConcurrentHashMap<>();

    DesktopSessionController(DesktopSessionService desktopSessions, AccountService accounts,
            CurrentPrincipal principal,
            @org.springframework.beans.factory.annotation.Value(
                    "${store.refresh.rate-limit-per-minute:30}") int rateLimitPerMinute) {
        this.desktopSessions = desktopSessions;
        this.accounts = accounts;
        this.principal = principal;
        this.rateLimitPerMinute = Math.max(1, rateLimitPerMinute);
    }

    private boolean overRateLimit(HttpServletRequest request) {
        String key = clientKey(request);
        long minute = System.currentTimeMillis() / 60_000;
        Window current = rate.compute(key, (k, w) ->
                w == null || w.minute() != minute ? new Window(minute, 1)
                        : new Window(minute, w.count() + 1));
        if (rate.size() > 10_000) {
            // Bounded memory: drop stale windows entirely, the next request re-seeds.
            rate.entrySet().removeIf(e -> e.getValue().minute() != minute);
        }
        return current.count() > rateLimitPerMinute;
    }

    /** Bearer-token body for the issued credential (token stays server-generated). */
    record TokenBody(String refreshToken) {}

    /** Issue response: the credential plus its expiry (sliding TTL). */
    record IssuedResponse(String refreshToken, String refreshExpiresAt) {}

    /** Refresh response mirrors the authorization server's token shape. */
    record RefreshResponse(String accessToken, long expiresIn, String refreshToken) {}

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
        return ip == null ? "unknown" : ip;
    }

    /** Issues the session's rotating credential — requires a valid access token. */
    @PostMapping("/desktop-session")
    public ResponseEntity<IssuedResponse> issue(HttpServletRequest request) {
        CurrentPrincipal.Principal current = principal.require();
        if (current.sessionId() == null) {
            throw new DomainException(StoreErrorCode.FORBIDDEN,
                    "This token carries no session to bind a desktop credential to");
        }
        IdentityRepositories.UserSessionRecord session = accounts
                .sessionForUser(current.userId(), current.sessionId());
        DesktopSessionService.IssuedCredential issued =
                desktopSessions.issue(session, accounts.userOrThrow(current.userId()));
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new IssuedResponse(issued.refreshToken(),
                        issued.expiresAt().toString()));
    }

    /** Single-use refresh with rotation; replay revokes the whole family. */
    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(@RequestBody TokenBody body,
            HttpServletRequest request) {
        if (overRateLimit(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Cache-Control", "no-store").build();
        }
        DesktopSessionService.RefreshedGrant grant = desktopSessions
                .refresh(body == null ? null : body.refreshToken())
                // Uniform 401 problem via the shared mapping (INVALID_CREDENTIALS).
                .orElseThrow(() -> new DomainException(StoreErrorCode.INVALID_CREDENTIALS,
                        "invalid_grant"));
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new RefreshResponse(grant.accessToken(), grant.expiresIn(),
                        grant.refreshToken()));
    }

    /**
     * Sign-out revocation. Uniformly 204: never confirms whether the presented
     * credential exists.
     */
    @PostMapping("/revoke")
    public ResponseEntity<Void> revoke(@RequestBody TokenBody body,
            HttpServletRequest request) {
        if (overRateLimit(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        desktopSessions.revoke(body == null ? null : body.refreshToken());
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
