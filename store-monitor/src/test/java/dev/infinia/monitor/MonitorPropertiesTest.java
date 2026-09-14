package dev.infinia.monitor;

import dev.infinia.monitor.config.MonitorProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MonitorPropertiesTest {

    /** All-defaults instance for tests that just need sane values. */
    public static MonitorProperties defaults() {
        return new MonitorProperties(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void defaultsAreProductionSane() {
        MonitorProperties properties = defaults();
        assertEquals(URI.create("http://localhost:8080"), properties.targetBaseUrl());
        assertEquals(5_000L, properties.probeIntervalMs());
        assertEquals(5_000L, properties.mirrorIntervalMs());
        assertEquals(8_000L, properties.probeTimeoutMs());
        assertEquals(8_000L, properties.mirrorTimeoutMs());
        assertEquals("", properties.alertWebhook());
        assertEquals(15, properties.alertThrottleMinutes());
        assertEquals(180_000, properties.staleAfterMs());
        assertEquals(90, properties.historyDays());
        assertEquals(2, properties.confirmFailureThreshold());
        assertEquals(2, properties.confirmRecoveryThreshold());
        assertEquals(180_000, properties.observationValidityMs());
        assertEquals(200, properties.sseMaxConnections());
        assertEquals(15_000, properties.sseHeartbeatMs());
        assertEquals(1_000, properties.sseReplayCapacity());
    }

    @Test
    void relativeMirrorDirRefusesToBoot() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new MonitorProperties(URI.create("https://store.example.com"), null,
                        null, null, null, "relative/dir", null, null, null, null,
                        null, null, null, null, null, null));
        assertEquals("monitor.mirror-dir must be an absolute path, got: relative/dir",
                error.getMessage());
    }

    @Test
    void targetBaseUrlMustBeAbsoluteHttp() {
        assertThrows(IllegalStateException.class, () -> new MonitorProperties(
                URI.create("store.example.com"), null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null));
        assertThrows(IllegalStateException.class, () -> new MonitorProperties(
                URI.create("ftp://store.example.com"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null));
    }
}
