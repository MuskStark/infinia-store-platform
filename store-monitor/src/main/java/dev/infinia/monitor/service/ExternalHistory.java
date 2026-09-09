package dev.infinia.monitor.service;

import dev.infinia.monitor.config.MonitorProperties;
import dev.infinia.monitor.persistence.ExternalDayEntity;
import dev.infinia.monitor.persistence.ExternalDayRepository;
import dev.infinia.store.contract.api.StatusDtos.ComponentDto;
import dev.infinia.store.contract.api.StatusDtos.DayDto;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day buckets for the components the monitor observes itself (external
 * reachability). The bucket math mirrors the store's own history rendering so
 * both halves of the merged page show identical bars — one honest lattice.
 */
@Component
public class ExternalHistory {

    public static final String COMPONENT_KEY = "external";

    private final ExternalDayRepository days;
    private final MonitorProperties properties;
    /** Latest probe outcome; no_data until the first poll after boot. */
    private final java.util.concurrent.atomic.AtomicReference<String> liveIndicator =
            new java.util.concurrent.atomic.AtomicReference<>(Indicators.NO_DATA);

    public ExternalHistory(ExternalDayRepository days, MonitorProperties properties) {
        this.days = days;
        this.properties = properties;
    }

    /** The freshest probe outcome — the live indicator the page renders. */
    public String liveIndicator() {
        return liveIndicator.get();
    }

    /** Records one probe outcome into today's bucket (accumulating upsert). */
    public void record(String indicator, Instant at) {
        liveIndicator.set(indicator);
        LocalDate day = LocalDate.ofInstant(at, ZoneOffset.UTC);
        ExternalDayEntity bucket = days
                .findById(new ExternalDayEntity.Key(COMPONENT_KEY, day))
                .orElseGet(() -> ExternalDayEntity.fresh(COMPONENT_KEY, day));
        switch (indicator) {
            case Indicators.MAJOR_OUTAGE, Indicators.PARTIAL_OUTAGE -> bucket.add(0, 0, 1);
            case Indicators.DEGRADED -> bucket.add(0, 1, 0);
            default -> bucket.add(1, 0, 0);
        }
        days.save(bucket);
        days.flush();
    }

    /** The external component's page section: live indicator + 90-day history. */
    public ComponentDto component(String liveIndicator) {
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
        return new ComponentDto(COMPONENT_KEY, liveIndicator, uptime, history);
    }

    /** Housekeeping outside the observation window. */
    public void prune() {
        days.deleteByDayBefore(LocalDate.now(ZoneOffset.UTC).minusDays(properties.historyDays() + 30L));
        days.flush();
    }
}
