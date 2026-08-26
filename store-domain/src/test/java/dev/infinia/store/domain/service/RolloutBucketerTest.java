package dev.infinia.store.domain.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RolloutBucketerTest {

    @Test
    void bucketIsStablePerInstallId() {
        RolloutBucketer bucketer = new RolloutBucketer("test-secret");
        int first = bucketer.bucket("install-abc");
        for (int i = 0; i < 100; i++) {
            assertEquals(first, bucketer.bucket("install-abc"));
        }
    }

    @Test
    void differentSecretsProduceDifferentCohorts() {
        int a = new RolloutBucketer("secret-a").bucket("install-abc");
        int b = new RolloutBucketer("secret-b").bucket("install-abc");
        assertNotEquals(a, b);
    }

    @Test
    void bucketsSpreadAcrossRange() {
        RolloutBucketer bucketer = new RolloutBucketer("spread-test");
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            int bucket = bucketer.bucket("install-" + i);
            assertTrue(bucket >= 0 && bucket < 100);
            seen.add(bucket);
        }
        // With 5000 samples all 100 buckets should appear.
        assertEquals(100, seen.size());
    }

    @Test
    void zeroPercentIncludesNobodyAndHundredIncludesEverybody() {
        RolloutBucketer bucketer = new RolloutBucketer("rollout");
        for (int i = 0; i < 50; i++) {
            String id = "install-" + i;
            assertFalse(bucketer.included(id, 0));
            assertTrue(bucketer.included(id, 100));
        }
    }

    @Test
    void blankInstallIdGetsBucketZero() {
        assertEquals(0, new RolloutBucketer("x").bucket(null));
        assertEquals(0, new RolloutBucketer("x").bucket("  "));
    }
}
