package dev.infinia.monitor.service;

import dev.infinia.monitor.config.MonitorProperties;
import dev.infinia.monitor.persistence.ExternalDayEntity;
import dev.infinia.monitor.persistence.ExternalDayRepository;
import dev.infinia.store.contract.api.StatusDtos.ComponentDto;
import dev.infinia.store.contract.api.StatusDtos.DayDto;
import dev.infinia.store.contract.status.ComponentStateMachine;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The monitor's own component: external reachability of the store's public
 * URL. Holds the confirmation state machine fed by every probe (confirmed
 * indicator, pending flag, observation timestamps) and the day buckets the
 * 90-day bars render from — the bucket math mirrors the store's own history
 * rendering so both halves of the merged page show identical bars.
 */
@Component
public class ExternalHistory {

    public static final String COMPONENT_KEY = "external";

    private final ExternalDayRepository days;
    private final MonitorProperties properties;
    /** Confirmed external state; unconfirmed (no_data) until the first probe. */
    private final ComponentStateMachine machine;

    public ExternalHistory(ExternalDayRepository days, MonitorProperties properties) {
        this.days = days;
        this.properties = properties;
        this.machine = new ComponentStateMachine(properties.confirmFailureThreshold(),
                properties.confirmRecoveryThreshold());
    }

    /** Feeds one raw probe outcome; the caller acts on the returned kind. */
    public ComponentStateMachine.Result observe(String rawIndicator, Instant at) {
        return machine.observe(rawIndicator, at);
    }

    /** The confirmed external state (indicator, pending, observation timestamps). */
    public ComponentStateMachine.State liveState() {
        return machine.state();
    }

    /** The confirmed indicator the page renders — freshest verdict that survived confirmation. */
    public String liveIndicator() {
        return machine.state().indicatorOrNoData();
    }

    /**
     * Records one confirmed outcome into today's bucket (accumulating upsert).
     * Called on confirmed transitions and by the per-minute rollup — a per-probe
     * cadence here would reweight day statistics as probing gets faster.
     */
    public void record(String indicator, Instant at) {
        if (!java.util.Set.of(Indicators.OPERATIONAL, Indicators.DEGRADED,
                Indicators.PARTIAL_OUTAGE, Indicators.MAJOR_OUTAGE, Indicators.NO_DATA).contains(indicator)) {
            throw new IllegalArgumentException("Unknown status indicator: " + indicator);
        }
        // Missing observations are neither successful nor failed samples.
        if (Indicators.NO_DATA.equals(indicator)) {
            return;
        }
        LocalDate day = LocalDate.ofInstant(at, ZoneOffset.UTC);
        ExternalDayEntity bucket = days
                .findById(new ExternalDayEntity.Key(COMPONENT_KEY, day))
                .orElseGet(() -> ExternalDayEntity.fresh(COMPONENT_KEY, day));
        switch (indicator) {
            case Indicators.MAJOR_OUTAGE, Indicators.PARTIAL_OUTAGE -> bucket.add(0, 0, 1);
            case Indicators.DEGRADED -> bucket.add(0, 1, 0);
            case Indicators.OPERATIONAL -> bucket.add(1, 0, 0);
            default -> throw new IllegalArgumentException("Unknown status indicator: " + indicator);
        }
        days.save(bucket);
        days.flush();
    }

    /** The external component's page section: confirmed live state + 90-day history. */
    public ComponentDto component() {
        int historyDays = properties.historyDays();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(historyDays - 1L);

        Map<LocalDate, ExternalDayEntity> byDay = new LinkedHashMap<>();
        for (ExternalDayEntity bucket : days
                .findByComponentAndDayGreaterThanEqual(COMPONENT_KEY, from)) {
            byDay.put(bucket.day, bucket);
        }

        List<DayDto> history = new ArrayList<>(historyDays);
        // Degraded samples count as availability (the statuspage.io convention,
        // mirrored with the store's own history): only down reduces the
        // number, so a sub-100% day can only ever appear orange/red.
        long available = 0;
        long total = 0;
        for (int i = 0; i < historyDays; i++) {
            LocalDate day = from.plusDays(i);
            ExternalDayEntity sample = byDay.get(day);
            if (sample == null || sample.ok + sample.degraded + sample.down == 0) {
                history.add(new DayDto(day.toString(), Indicators.NO_DATA, null));
                continue;
            }
            long dayTotal = sample.ok + sample.degraded + sample.down;
            long dayAvailable = sample.ok + sample.degraded;
            available += dayAvailable;
            total += dayTotal;
            double uptimePercent = Math.round(1000.0 * dayAvailable / dayTotal) / 10.0;
            String dayIndicator;
            if (sample.down > 0) {
                dayIndicator = sample.down == dayTotal
                        ? Indicators.MAJOR_OUTAGE : Indicators.PARTIAL_OUTAGE;
            } else if (sample.degraded > 0) {
                dayIndicator = Indicators.DEGRADED;
            } else {
                dayIndicator = Indicators.OPERATIONAL;
            }
            history.add(new DayDto(day.toString(), dayIndicator, uptimePercent));
        }
        Double uptime = total == 0 ? null : Math.round(1000.0 * available / total) / 10.0;

        ComponentStateMachine.State state = machine.state();
        boolean stale = state.observedAt() == null || Instant.now()
                .isAfter(state.observedAt().plusMillis(properties.observationValidityMs()));
        return new ComponentDto(COMPONENT_KEY, state.indicatorOrNoData(), uptime, history,
                state.observedAt() == null ? null : state.observedAt().toString(),
                state.lastSuccessAt() == null ? null : state.lastSuccessAt().toString(),
                state.pending(), stale);
    }

    /** Housekeeping outside the observation window. */
    public void prune() {
        days.deleteByDayBefore(LocalDate.now(ZoneOffset.UTC).minusDays(properties.historyDays() + 30L));
        days.flush();
    }
}
