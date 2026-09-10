package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /api/v1/platform-keys is the trust-anchor bootstrap channel (design §8.3):
 * the FengYu host's operator provisions {@code trusted-store-keys.json} from
 * it to run with {@code require-signature=true}. Public keys only — JWKS
 * precedent; assert both the anonymous reachability and that the served
 * material is a usable Ed25519 anchor, not just a non-empty string.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:platform-key-api;"
                        + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class PlatformKeyApiTest {

    @LocalServerPort
    int port;

    @Test
    @SuppressWarnings("unchecked")
    void servesActivePlatformAnchorAnonymously() throws Exception {
        ResponseEntity<Map> response =
                new Http(port).getJson("/api/v1/platform-keys", Map.class, null);
        assertEquals(200, response.getStatusCode().value());

        List<Map<String, Object>> keys = (List<Map<String, Object>>) response.getBody().get("keys");
        assertFalse(keys.isEmpty(), "the platform must register at least one ACTIVE key");
        Map<String, Object> key = keys.get(0);
        assertTrue(((String) key.get("keyId")).startsWith("platform-ed25519-"),
                "keyId: " + key.get("keyId"));
        assertEquals("Ed25519", key.get("algorithm"));

        // The anchor must parse as the exact key type the host trust registry loads
        // (JDK reports the family name "EdDSA" for a parsed Ed25519 key).
        byte[] der = java.util.Base64.getDecoder().decode((String) key.get("publicKeyBase64"));
        java.security.PublicKey parsed = java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(der));
        assertTrue(parsed instanceof java.security.interfaces.EdECPublicKey,
                "parsed as " + parsed.getAlgorithm());

        // Public-key document only — no private material may leak into the view
        // (null validFrom/validTo are omitted by the NON_NULL serialization).
        assertTrue(key.keySet().stream().allMatch(java.util.Set.of(
                        "keyId", "algorithm", "publicKeyBase64", "validFrom", "validTo")::contains),
                "fields: " + key.keySet());
    }
}
