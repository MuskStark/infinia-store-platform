package dev.infinia.store.domain.service;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 generator (time-ordered, index-friendly identifiers, design §10.1).
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    public static UUID generate(long unixMillis) {
        byte[] value = new byte[16];
        RANDOM.nextBytes(value);
        // 48-bit big-endian unix millisecond timestamp overwrites the random prefix
        value[0] = (byte) (unixMillis >>> 40);
        value[1] = (byte) (unixMillis >>> 32);
        value[2] = (byte) (unixMillis >>> 24);
        value[3] = (byte) (unixMillis >>> 16);
        value[4] = (byte) (unixMillis >>> 8);
        value[5] = (byte) (unixMillis);
        // version 7
        value[6] = (byte) ((value[6] & 0x0F) | 0x70);
        // RFC 4122 variant
        value[8] = (byte) ((value[8] & 0x3F) | 0x80);

        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (value[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (value[i] & 0xFF);
        }
        return new UUID(msb, lsb);
    }
}
