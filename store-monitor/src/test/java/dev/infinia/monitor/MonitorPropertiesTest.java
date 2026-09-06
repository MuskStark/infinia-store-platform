package dev.infinia.monitor;

import dev.infinia.monitor.config.MonitorProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MonitorPropertiesTest {

    @Test
    void defaultsAreProductionSane() {
        MonitorProperties properties = new MonitorProperties(null, null, null, null, null,
                null, null, null);
        assertEquals(URI.create("http://localhost:8080"), properties.targetBaseUrl());
        assertEquals(60_000L, properties.pollIntervalMs());
        assertEquals(8_000L, properties.probeTimeoutMs());
        assertEquals("", properties.alertWebhook());
        assertEquals(15, properties.alertThrottleMinutes());
        assertEquals(180_000, properties.staleAfterMs());
        assertEquals(90, properties.historyDays());
    }

    @Test
    void relativeMirrorDirRefusesToBoot() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new MonitorProperties(URI.create("https://store.example.com"), null,
                        null, "relative/dir", null, null, null, null));
        assertEquals("monitor.mirror-dir must be an absolute path, got: relative/dir",
                error.getMessage());
    }

    @Test
    void targetBaseUrlMustBeAbsoluteHttp() {
        assertThrows(IllegalStateException.class, () -> new MonitorProperties(
                URI.create("store.example.com"), null, null, null, null, null, null, null));
        assertThrows(IllegalStateException.class, () -> new MonitorProperties(
                URI.create("ftp://store.example.com"), null, null, null, null, null, null,
                null));
    }
}
