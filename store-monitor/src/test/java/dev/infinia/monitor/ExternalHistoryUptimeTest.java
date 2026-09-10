package dev.infinia.monitor;

import dev.infinia.monitor.persistence.ExternalDayEntity;
import dev.infinia.monitor.persistence.ExternalDayRepository;
import dev.infinia.monitor.service.ExternalHistory;
import dev.infinia.monitor.service.Indicators;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day-bucket uptime semantics: degraded samples count as availability (the
 * statuspage.io convention, mirrored with the store's own history) — only
 * down reduces the percentage. Before this rule a day of pure degraded
 * probes rendered as "0.00% uptime" colored yellow, which reads as a bug:
 * 0% availability must only ever appear on an outage-colored (orange/red)
 * day.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Polling is driven manually in tests; no startup sample races
                // these writes to today's bucket.
                "monitor.poll-interval-ms=3600000",
                "monitor.poll-initial-delay-ms=3600000",
                "spring.datasource.url=jdbc:h2:mem:monitor-uptime;"
                        + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        })
class ExternalHistoryUptimeTest {

    @Autowired
    ExternalHistory history;

    @Autowired
    ExternalDayRepository days;

    @Test
    void degradedCountsAsAvailabilityOnlyDownReducesUptime() {
        history.record(Indicators.DEGRADED, Instant.now());
        history.record(Indicators.MAJOR_OUTAGE, Instant.now());

        ExternalDayEntity bucket = days
                .findById(new ExternalDayEntity.Key(ExternalHistory.COMPONENT_KEY,
                        LocalDate.now(ZoneOffset.UTC)))
                .orElseThrow();
        long total = bucket.ok + bucket.degraded + bucket.down;
        long available = bucket.ok + bucket.degraded;
        assertTrue(bucket.degraded > 0, "the degraded sample must be in the bucket");

        var component = history.component(Indicators.DEGRADED);
        var today = component.history().get(component.history().size() - 1);

        double expected = Math.round(1000.0 * available / total) / 10.0;
        assertEquals(expected, today.uptimePercent(), 0.001,
                "uptime counts degraded samples as available");
        assertTrue(today.uptimePercent() > 100.0 * bucket.ok / total,
                "the degraded sample lifted the percentage above ok-only math");
        assertEquals(expected, component.uptime90d(), 0.001);

        // A bucket with down samples must never be green or yellow.
        if (bucket.down > 0) {
            assertTrue(Indicators.rank(today.indicator()) >= Indicators.rank(
                    Indicators.PARTIAL_OUTAGE), "down>0 must color the day orange or red");
        }
    }
}
