package dev.infinia.store.app.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.infinia.store.app.config.KeyMaterial;
import dev.infinia.store.domain.model.StoreUser;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.service.UuidV7;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Mints access tokens for direct (non-OAuth) logins (design §7.2). The result is
 * byte-compatible with the authorization server's tokens: same RSA key, same
 * uid/roles/email/sid claims, and a session-ledger row so the session appears in
 * /me/sessions and can be revoked.
 */
@Service
public class LocalTokenService {

    private static final Duration ACCESS_TTL = Duration.ofMinutes(30);

    private final KeyMaterial keyMaterial;
    private final IdentityRepositories.SessionRepository sessions;
    private final String issuer;

    public LocalTokenService(KeyMaterial keyMaterial,
            IdentityRepositories.SessionRepository sessions,
            dev.infinia.store.app.config.StoreProperties properties) {
        this.keyMaterial = keyMaterial;
        this.sessions = sessions;
        this.issuer = properties.baseUrl();
    }

    public String mint(StoreUser user, String clientId) {
        UUID sessionId = UuidV7.generate();
        Instant now = Instant.now();
        sessions.save(new IdentityRepositories.UserSessionRecord(sessionId, user.id, clientId,
                "PASSWORD", null, now, null, false, null));

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID("store-jwt-1")
                .build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .jwtID(UUID.randomUUID().toString())
                .subject(user.id.toString())
                .claim("uid", user.id.toString())
                .claim("roles", user.roles.stream().map(Enum::name).toList())
                .claim("email", user.email)
                .claim("sid", sessionId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ACCESS_TTL)))
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(keyMaterial.jwtKeyPair().getPrivate()));
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Failed to sign access token", e);
        }
        return jwt.serialize();
    }
}
