package dev.infinia.store.contract.status;

import dev.infinia.store.contract.api.StatusDtos;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Interval-based availability math, shared by the store's own history and the
 * standalone monitor so both halves of the merged page count alike:
 *
 * <ul>
 *   <li>uptime = (operational + degraded) ÷ observed — partial and major
 *       outages count as unavailable (a deliberately conservative reading);</li>
 *   <li>coverage = observed ÷ window — time beyond the observation validity,
 *       before the stats cutover, or simply unobserved is unknown, never
 *       silently "100%";</li>
 *   <li>days are UTC; intervals crossing midnight are split per day; today is
 *       capped at {@code now}.</li>
 * </ul>
 */
public final class StatusIntervals {

    private StatusIntervals() {}

    /**
     * One component's per-day aggregate. {@code uptimePercent()} is null for
     * days without observations; {@code coveragePercentOf(windowMillis)}
     * discloses how much of the day the observations actually covered.
     */
    public record DayStat(LocalDate day, String indicator, long observedMillis,
            long availableMillis) {

        public Double uptimePercent() {
            return observedMillis == 0 ? null
                    : Math.round(1000.0 * availableMillis / observedMillis) / 10.0;
        }

        public Double coveragePercentOf(long windowMillis) {
            if (windowMillis <= 0) {
                return 0.0;
            }
            return Math.round(1000.0 * Math.min(observedMillis, windowMillis) / windowMillis)
                    / 10.0;
        }
    }

    /** One day's contribution to the window uptime blend. */
    public record DayPart(double ratio, long weightMillis) {}

    /**
     * Aggregates intervals into per-day stats for {@code days} days starting
     * at {@code from} (UTC). The ongoing interval (null end) is capped at
     * {@code now}.
     */
    public static List<DayStat> daily(List<StatusInterval> intervals, LocalDate from, int days,
            Instant now) {
        List<DayStat> stats = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate day = from.plusDays(i);
            Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant dayEnd = dayStart.plus(Duration.ofDays(1));
            long observed = 0;
            long available = 0;
            long down = 0;
            long degraded = 0;
            for (StatusInterval interval : intervals) {
                Instant start = interval.startedAt().isBefore(dayStart) ? dayStart
                        : interval.startedAt();
                Instant rawEnd = interval.endedAt() == null || interval.endedAt()
                        .isAfter(now) ? now : interval.endedAt();
                Instant end = rawEnd.isAfter(dayEnd) ? dayEnd : rawEnd;
                if (!end.isAfter(start)) {
                    continue;
                }
                long millis = Duration.between(start, end).toMillis();
                observed += millis;
                switch (interval.indicator()) {
                    case StatusDtos.OPERATIONAL -> available += millis;
                    case StatusDtos.DEGRADED -> {
                        available += millis;
                        degraded += millis;
                    }
                    default -> down += millis;
                }
            }
            stats.add(new DayStat(day, dayIndicator(observed, down, degraded), observed,
                    available));
        }
        return stats;
    }

    /** The day's colour: any down is orange/red (red only when all of it was), any degraded yellow. */
    private static String dayIndicator(long observed, long down, long degraded) {
        if (observed == 0) {
            return StatusDtos.NO_DATA;
        }
        if (down > 0) {
            return down == observed ? StatusDtos.MAJOR_OUTAGE : StatusDtos.PARTIAL_OUTAGE;
        }
        return degraded > 0 ? StatusDtos.DEGRADED : StatusDtos.OPERATIONAL;
    }

    /** The window's uptime: each day's ratio weighted by its observed time (capped at a day). */
    public static Double blendedUptime(List<DayPart> parts) {
        double weighted = 0;
        long weight = 0;
        for (DayPart part : parts) {
            weighted += part.ratio() * part.weightMillis();
            weight += part.weightMillis();
        }
        return weight == 0 ? null : Math.round(1000.0 * weighted / weight) / 10.0;
    }

    /** Milliseconds of a UTC day, or the elapsed part of today. */
    public static long windowMillis(LocalDate day, Instant now) {
        Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = dayStart.plus(Duration.ofDays(1));
        Instant cap = now.isBefore(dayEnd) ? now : dayEnd;
        return Math.max(0, Duration.between(dayStart, cap).toMillis());
    }
}
