package dev.infinia.store.domain.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UuidV7Test {

    @Test
    void setsVersionAndVariant() {
        UUID id = UuidV7.generate();
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    void isMonotonicallyOrderedByTime() {
        UUID early = UuidV7.generate(1_700_000_000_000L);
        UUID late = UuidV7.generate(1_700_000_000_001L);
        assertTrue(early.compareTo(late) < 0);
    }

    @Test
    void encodesTimestampInMillis() {
        long ts = 1_756_089_600_000L; // 2025-08-25T00:00:00Z
        UUID id = UuidV7.generate(ts);
        long extracted = id.getMostSignificantBits() >>> 16;
        assertEquals(ts, extracted);
    }
}
