package dev.infinia.monitor.service;

import dev.infinia.store.contract.api.StatusDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One poll cycle, every {@code monitor.poll-interval-ms}: probe the store's
 * public URL, record the external bucket and incidents, mirror the store's
 * status snapshot (a failed fetch freezes the last one), then diff the merged
 * indicators for alerting. The order matters: probe first, so a store that is
 * going down reports a red external component even if the mirror fetch races
 * the shutdown.
 */
@Component
public class PollCycle {

    private static final Logger log = LoggerFactory.getLogger(PollCycle.class);

    private final TargetProber prober;
    private final StatusMirror mirror;
    private final ExternalHistory history;
    private final MonitorIncidentService incidents;
    private final AlertService alerts;
    private LocalDate lastPruneDay = null;

    public PollCycle(TargetProber prober, StatusMirror mirror, ExternalHistory history,
            MonitorIncidentService incidents, AlertService alerts) {
        this.prober = prober;
        this.mirror = mirror;
        this.history = history;
        this.incidents = incidents;
        this.alerts = alerts;
    }

    @Scheduled(fixedDelayString = "${monitor.poll-interval-ms:60000}",
            initialDelayString = "${monitor.poll-initial-delay-ms:0}")
    public void scheduled() {
        try {
            cycle();
        } catch (Exception e) {
            // A poll failure must never kill the scheduler.
            log.warn("Poll cycle failed: {}", e.getMessage());
        }
    }

    public void cycle() {
        Instant now = Instant.now();
        String external = prober.probe();
        history.record(external, now);
        incidents.track(external, now);
        mirror.fetch();
        pruneDaily(now);
        alerts.onIndicators(mergedIndicators(external), now);
    }

    /** Mirrored components keep their last-known indicator; external is live. */
    private Map<String, String> mergedIndicators(String external) {
        Map<String, String> indicators = new LinkedHashMap<>();
        StatusMirror.Snapshot snapshot = mirror.current();
        if (snapshot != null) {
            for (StatusDtos.ComponentDto component : snapshot.page().components()) {
                indicators.put(component.key(), component.indicator());
            }
        }
        indicators.put(ExternalHistory.COMPONENT_KEY, external);
        return indicators;
    }

    private void pruneDaily(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!today.equals(lastPruneDay)) {
            history.prune();
            lastPruneDay = today;
        }
    }
}
