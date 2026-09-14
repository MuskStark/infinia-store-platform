package dev.infinia.monitor.service;

import dev.infinia.monitor.config.MonitorProperties;
import dev.infinia.store.contract.api.StatusDtos;
import dev.infinia.store.contract.status.ComponentStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The monitor's heartbeat, split into independently scheduled rounds so one
 * slow check can never hold up another (each {@code @Scheduled} method runs
 * with fixedDelay semantics — the same check never overlaps itself — and the
 * sized scheduler pool runs them on separate threads):
 * <ul>
 *   <li>{@link #probeCycle()} — probe the store's public URL every
 *       {@code monitor.probe-interval-ms} and feed the confirmation machine;</li>
 *   <li>{@link #mirrorCycle()} — fetch the store's status snapshot every
 *       {@code monitor.mirror-interval-ms} (a failed fetch freezes the last
 *       one) and diff it for alerting;</li>
 *   <li>{@link #rollupCycle()} — once a minute, record the confirmed external
 *       state into the day buckets (keeping statistics weights independent of
 *       the probe cadence) and prune old history.</li>
 * </ul>
 * Probing runs before mirroring at boot, so a store that is going down
 * reports a red external component even if the mirror fetch races the
 * shutdown.
 */
@Component
public class PollCycle {

    private static final Logger log = LoggerFactory.getLogger(PollCycle.class);

    private final TargetProber prober;
    private final StatusMirror mirror;
    private final ExternalHistory history;
    private final MonitorIncidentService incidents;
    private final AlertService alerts;
    private final StatusEventBus events;
    private final IntervalStatistics intervalStatistics;
    private final MonitorProperties properties;
    private LocalDate lastPruneDay = null;
    /** Last mirror state published per component (indicator + staleness). */
    private final Map<String, MirrorView> lastMirrorViews = new LinkedHashMap<>();
    /** Mirror incidents already pushed, by id → updatedAt. */
    private final Map<String, String> lastMirrorIncidents = new LinkedHashMap<>();

    public PollCycle(TargetProber prober, StatusMirror mirror, ExternalHistory history,
            MonitorIncidentService incidents, AlertService alerts, StatusEventBus events,
            IntervalStatistics intervalStatistics, MonitorProperties properties) {
        this.prober = prober;
        this.mirror = mirror;
        this.history = history;
        this.incidents = incidents;
        this.alerts = alerts;
        this.events = events;
        this.intervalStatistics = intervalStatistics;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${monitor.probe-interval-ms:5000}",
            initialDelayString = "${monitor.probe-initial-delay-ms:0}")
    public void probeCycle() {
        try {
            probeOnce();
        } catch (Exception e) {
            // A probe failure must never kill the scheduler.
            log.warn("Probe cycle failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${monitor.mirror-interval-ms:5000}",
            initialDelayString = "${monitor.mirror-initial-delay-ms:200}")
    public void mirrorCycle() {
        try {
            mirrorOnce();
        } catch (Exception e) {
            log.warn("Mirror cycle failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${monitor.rollup-interval-ms:60000}",
            initialDelayString = "${monitor.rollup-initial-delay-ms:1000}")
    public void rollupCycle() {
        try {
            rollupOnce();
        } catch (Exception e) {
            log.warn("Rollup cycle failed: {}", e.getMessage());
        }
    }

    /** One full round (probe + mirror); the integration tests drive this manually. */
    public void cycle() {
        probeOnce();
        mirrorOnce();
    }

    private void probeOnce() {
        Instant now = Instant.now();
        TargetProber.Outcome outcome = prober.probe();
        ComponentStateMachine.Result result = history.observe(outcome.indicator(), now);
        switch (result.kind()) {
            case CONFIRMED -> {
                String confirmed = result.state().indicatorOrNoData();
                history.record(confirmed, now);
                intervalStatistics.transition(ExternalHistory.COMPONENT_KEY, confirmed, now,
                        IntervalStatistics.SOURCE_EXTERNAL_PROBE);
                publishComponent(now); // the cell flips before its incident appears
                for (StatusDtos.IncidentDto incident : incidents.track(confirmed, now)) {
                    events.incidentUpdated(incident);
                }
            }
            case PENDING, PENDING_CLEARED -> publishComponent(now);
            case STEADY -> { /* observedAt refreshes silently with every read */ }
        }
    }

    private void mirrorOnce() {
        Instant now = Instant.now();
        Optional<StatusMirror.Snapshot> fetched = mirror.fetch();
        StatusMirror.Snapshot snapshot = mirror.current();
        if (snapshot == null) {
            return; // never reached the store; the cold-start page says so already
        }
        boolean stale = now.isAfter(snapshot.fetchedAt().plusMillis(properties.staleAfterMs()));
        // Mirrored components publish on (indicator, stale) diffs — the store
        // transitioned, or the frozen view crossed the staleness window.
        Map<String, String> merged = mergedIndicators(history.liveIndicator());
        String overall = Indicators.worst(merged.values());
        for (StatusDtos.ComponentDto component : snapshot.page().components()) {
            MirrorView view = new MirrorView(component.indicator(), stale);
            if (!view.equals(lastMirrorViews.get(component.key()))) {
                lastMirrorViews.put(component.key(), view);
                events.componentUpdated(stale ? withStale(component) : component,
                        overall, now.toString());
            }
        }
        if (fetched.isPresent()) {
            // The mirror's frozen internals never flap; a diff here means the
            // store itself transitioned — worth alerting on immediately.
            alerts.onIndicators(merged, now);
            for (StatusDtos.IncidentDto incident : snapshot.incidents()) {
                if (!incident.updatedAt().equals(lastMirrorIncidents.get(incident.incidentId()))) {
                    lastMirrorIncidents.put(incident.incidentId(), incident.updatedAt());
                    events.incidentUpdated(incident);
                }
            }
        }
    }

    private void rollupOnce() {
        Instant now = Instant.now();
        String confirmed = history.liveState().indicator();
        if (confirmed != null && !Indicators.NO_DATA.equals(confirmed)) {
            history.record(confirmed, now);
            // Observations older than the validity window stop counting: close
            // the open interval at the boundary, then reopen from now (the gap
            // stays unknown, disclosed by coverage). Steady state is a no-op.
            ComponentStateMachine.State state = history.liveState();
            Instant expiry = state.observedAt().plusMillis(properties.observationValidityMs());
            if (now.isAfter(expiry)) {
                intervalStatistics.expireOpen(ExternalHistory.COMPONENT_KEY, expiry);
            }
            intervalStatistics.transition(ExternalHistory.COMPONENT_KEY, confirmed, now,
                    IntervalStatistics.SOURCE_EXTERNAL_PROBE);
        }
        // The per-minute history refresh: today's bar moved for everyone.
        StatusDtos.ComponentDto external = history.component();
        events.historyUpdated(external.key(), external.uptime90d(), external.history());
        pruneDaily(now);
    }

    /** A frozen mirrored component must wear its staleness on the cell itself. */
    private static StatusDtos.ComponentDto withStale(StatusDtos.ComponentDto component) {
        return new StatusDtos.ComponentDto(component.key(), component.indicator(),
                component.uptime90d(), component.history(), component.observedAt(),
                component.lastSuccessAt(), component.pending(), true);
    }

    /** What the mirror last published per component — indicator and staleness. */
    private record MirrorView(String indicator, boolean stale) {}

    private void publishComponent(Instant now) {
        Map<String, String> merged = mergedIndicators(history.liveIndicator());
        events.componentUpdated(history.component(), Indicators.worst(merged.values()),
                now.toString());
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
            intervalStatistics.prune(now.minus(
                    java.time.Duration.ofDays(properties.historyDays() + 30L)));
            lastPruneDay = today;
        }
    }
}
