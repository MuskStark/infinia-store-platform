package dev.infinia.store.app;

import dev.infinia.store.app.config.StoreProperties.Monitoring;
import dev.infinia.store.app.service.StatusService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boundary classification for the comprehensive-monitoring probes. The probes
 * themselves read live MXBeans; these tests pin the ladder so a threshold
 * refactor cannot silently shift the indicator semantics.
 */
class StatusThresholdsTest {

    private static final Monitoring DEFAULTS = new Monitoring(null, null, null, null, null,
            null, null, null, null, null);

    @Test
    void diskAndMemoryUseOneFreePercentLadder() {
        assertEquals(StatusService.MAJOR_OUTAGE, StatusService.classifyFreePercent(3, 5, 15));
        assertEquals(StatusService.MAJOR_OUTAGE, StatusService.classifyFreePercent(5, 5, 15));
        assertEquals(StatusService.DEGRADED, StatusService.classifyFreePercent(5.01, 5, 15));
        assertEquals(StatusService.DEGRADED, StatusService.classifyFreePercent(15, 5, 15));
        assertEquals(StatusService.OPERATIONAL, StatusService.classifyFreePercent(15.01, 5, 15));
    }

    @Test
    void poolDegradesOnSaturationAndAwaitingThreads() {
        assertEquals(StatusService.OPERATIONAL,
                StatusService.classifyPool(6, 10, 0, DEFAULTS));
        assertEquals(StatusService.DEGRADED,
                StatusService.classifyPool(8, 10, 0, DEFAULTS));
        // Even one thread waiting for a connection is a latency signal.
        assertEquals(StatusService.DEGRADED,
                StatusService.classifyPool(1, 10, 1, DEFAULTS));
        assertEquals(StatusService.MAJOR_OUTAGE,
                StatusService.classifyPool(10, 10, 5, DEFAULTS));
        assertEquals(StatusService.OPERATIONAL,
                StatusService.classifyPool(0, 0, 0, DEFAULTS), "idle pool is healthy");
    }

    @Test
    void httpWindowNeedsVolumeBeforeJudgingErrorRatio() {
        // Too little traffic: never escalate on the ratio, p95 still applies.
        assertEquals(StatusService.OPERATIONAL,
                StatusService.classifyHttpWindow(3, 3, 100, DEFAULTS));
        assertEquals(StatusService.DEGRADED,
                StatusService.classifyHttpWindow(3, 0, 2000, DEFAULTS));
        // Enough traffic: the 5xx ladder engages.
        assertEquals(StatusService.OPERATIONAL,
                StatusService.classifyHttpWindow(100, 0, 100, DEFAULTS));
        assertEquals(StatusService.PARTIAL_OUTAGE,
                StatusService.classifyHttpWindow(100, 1, 100, DEFAULTS));
        assertEquals(StatusService.MAJOR_OUTAGE,
                StatusService.classifyHttpWindow(100, 5, 100, DEFAULTS));
        // A missing histogram (p95 -1) never degrades on its own.
        assertEquals(StatusService.OPERATIONAL,
                StatusService.classifyHttpWindow(100, 0, -1, DEFAULTS));
    }

    @Test
    void absentThresholdsFallBackToDefaults() {
        Monitoring defaults = new Monitoring(null, null, null, null, null, null, null, null,
                null, null);
        assertEquals(15, defaults.diskWarnFreePercent());
        assertEquals(5, defaults.diskCriticalFreePercent());
        assertEquals(85, defaults.heapWarnUsedPercent());
        assertEquals(80, defaults.poolWarnActivePercent());
        assertEquals(1.0, defaults.http5xxWarnPercent(), 1e-9);
        assertEquals(5.0, defaults.http5xxCriticalPercent(), 1e-9);
        assertEquals(1500, defaults.httpP95WarnMillis());
    }
}
