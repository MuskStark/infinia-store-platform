package dev.infinia.store.contract.status;

import dev.infinia.store.contract.api.StatusDtos;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The shared availability math: day splitting, unknown gaps, coverage, and
 *  the conservative "outage time is unavailable" reading. */
class StatusIntervalsTest {

    private static final Instant T0 = Instant.parse("2026-09-14T00:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-09-14");

    private static StatusInterval interval(String indicator, String start, String end) {
        return new StatusInterval("external", indicator, Instant.parse(start),
                end == null ? null : Instant.parse(end));
    }

    @Test
    void splitsIntervalsAcrossMidnightAndCapsOngoingAtNow() {
        var intervals = List.of(
                interval("operational", "2026-09-13T22:00:00Z", "2026-09-14T01:00:00Z"),
                interval("major_outage", "2026-09-14T01:00:00Z", null)); // ongoing
        var stats = StatusIntervals.daily(intervals, DAY, 1, T0.plusSeconds(3 * 3600));

        var day = stats.get(0);
        assertEquals(3 * 3_600_000L, day.observedMillis(), "3h observed: 1h ok + 2h ongoing outage");
        assertEquals(1 * 3_600_000L, day.availableMillis(), "outage time is not available time");
        assertEquals(33.3, day.uptimePercent());
        assertEquals(StatusIntervals.windowMillis(DAY, T0.plusSeconds(3 * 3600)),
                3 * 3_600_000L);
        assertEquals(100.0, day.coveragePercentOf(3 * 3_600_000L), "fully observed so far");
        assertEquals(StatusDtos.PARTIAL_OUTAGE, day.indicator(), "mixed day colours orange");
    }

    @Test
    void unknownGapsAreExcludedFromUptimeButShownByCoverage() {
        var intervals = List.of(
                interval("operational", "2026-09-14T00:00:00Z", "2026-09-14T06:00:00Z"),
                interval("operational", "2026-09-14T18:00:00Z", null));
        var stats = StatusIntervals.daily(intervals, DAY, 1, T0.plus(DurationHours(24)));
        var day = stats.get(0);

        assertEquals(100.0, day.uptimePercent(), "all observed time was healthy");
        assertEquals(50.0, day.coveragePercentOf(StatusIntervals.windowMillis(DAY,
                T0.plus(DurationHours(24)))), "12h of the day is unknown, not '100% uptime'");
    }

    @Test
    void partialOutageCountsAsUnavailableAndDegradedAsAvailable() {
        var intervals = List.of(
                interval("operational", "2026-09-14T00:00:00Z", "2026-09-14T12:00:00Z"),
                interval("degraded", "2026-09-14T12:00:00Z", "2026-09-14T18:00:00Z"),
                interval("partial_outage", "2026-09-14T18:00:00Z", null));
        var day = StatusIntervals.daily(intervals, DAY, 1, T0.plus(DurationHours(24))).get(0);

        assertEquals(75.0, day.uptimePercent(), "degraded counts, partial does not");
        assertEquals(StatusDtos.PARTIAL_OUTAGE, day.indicator());
    }

    @Test
    void fullyDownDayIsMajorAndUnobservedDayIsNoData() {
        var down = List.of(interval("major_outage", "2026-09-14T00:00:00Z", null));
        var full = StatusIntervals.daily(down, DAY, 1, T0.plus(DurationHours(24))).get(0);
        assertEquals(StatusDtos.MAJOR_OUTAGE, full.indicator());
        assertEquals(0.0, full.uptimePercent());

        var none = StatusIntervals.daily(List.of(), DAY.plusDays(1), 1, T0.plus(DurationHours(24))).get(0);
        assertEquals(StatusDtos.NO_DATA, none.indicator());
        assertNull(none.uptimePercent());
        assertEquals(0L, none.observedMillis());
    }

    @Test
    void windowUptimeBlendsDayRatiosByObservedWeight() {
        assertEquals(75.0, StatusIntervals.blendedUptime(List.of(
                new StatusIntervals.DayPart(1.0, 86_400_000L),
                new StatusIntervals.DayPart(0.5, 86_400_000L))));
        // A 12h-observed 80% half-day weighs one third of a full perfect day:
        // (1.0·86400s + 0.8·43200s) / 129600s = 93.3%.
        assertEquals(93.3, StatusIntervals.blendedUptime(List.of(
                new StatusIntervals.DayPart(1.0, 86_400_000L),
                new StatusIntervals.DayPart(0.8, 43_200_000L))));
        assertNull(StatusIntervals.blendedUptime(List.of()));
    }

    @Test
    void todayWindowIsElapsedNotTheFullDay() {
        assertEquals(7 * 3_600_000L,
                StatusIntervals.windowMillis(DAY, T0.plus(DurationHours(7))));
        assertEquals(86_400_000L,
                StatusIntervals.windowMillis(DAY.minusDays(1), T0.plus(DurationHours(7))));
        assertEquals(0L, StatusIntervals.windowMillis(DAY.plusDays(1), T0));
    }

    @Test
    void intervalValidationRejectsNonsense() {
        assertThrows(IllegalArgumentException.class,
                () -> interval("operational", "2026-09-14T02:00:00Z", "2026-09-14T01:00:00Z"));
        assertThrows(IllegalArgumentException.class,
                () -> new StatusInterval(null, "operational", T0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new StatusInterval("external", "no_data", T0, null));
    }

    private static java.time.Duration DurationHours(int hours) {
        return java.time.Duration.ofHours(hours);
    }
}
