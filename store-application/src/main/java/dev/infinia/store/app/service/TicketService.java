package dev.infinia.store.app.service;

import dev.infinia.store.app.config.StoreProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Short-lived, purpose-limited HMAC tickets used as local presigned-URL equivalents
 * for uploads and downloads (design §10.2).
 */
@Service
public class TicketService {

    private final byte[] secret;

    public TicketService(StoreProperties properties) {
        this.secret = properties.ticketSecret().getBytes(StandardCharsets.UTF_8);
    }

    public record Ticket(String purpose, String subject, Instant expiresAt, String signature) {}

    public String sign(String purpose, String subject, Instant expiresAt) {
        String payload = purpose + ":" + subject + ":" + expiresAt.getEpochSecond();
        return hmac(payload);
    }

    public boolean verify(String purpose, String subject, Instant expiresAt, String signature) {
        String expected = sign(purpose, subject, expiresAt);
        return constantTimeEquals(expected, signature == null ? "" : signature)
                && Instant.now().isBefore(expiresAt);
    }

    public static String encodeTicketParams(String purpose, String subject, Instant expiresAt,
            String signature) {
        return "purpose=" + purpose + "&subject=" + subject + "&exp=" + expiresAt.getEpochSecond()
                + "&sig=" + signature;
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
