package dev.infinia.monitor.service;

import dev.infinia.monitor.persistence.StatusIntervalEntity;
import dev.infinia.monitor.persistence.StatusIntervalRepository;
import dev.infinia.store.contract.status.StatusInterval;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Writes and reads the interval statistics: one row per confirmed-state
 * stretch. Transitions close the open interval and open the next (a no-op
 * when the open interval already holds the indicator, so steady state stays
 * a single row); observation expiry closes the open interval at the validity
 * boundary, leaving an unknown gap that coverage — never uptime — absorbs.
 */
@Component
public class IntervalStatistics {

    public static final String SOURCE_EXTERNAL_PROBE = "external-probe";

    private final StatusIntervalRepository intervals;

    public IntervalStatistics(StatusIntervalRepository intervals) {
        this.intervals = intervals;
    }

    /** Closes the open interval at {@code at} and opens one for the indicator. */
    @Transactional
    public void transition(String component, String indicator, Instant at, String source) {
        var open = intervals.findFirstByComponentAndEndedAtIsNullOrderByStartedAtDesc(component);
        if (open.isPresent()) {
            if (open.get().indicator.equals(indicator)) {
                return; // steady state is one ongoing interval
            }
            if (at.isAfter(open.get().startedAt)) {
                open.get().endedAt = at;
                intervals.save(open.get());
            } else {
                return; // a same-instant transition cannot split time
            }
        }
        StatusIntervalEntity fresh = new StatusIntervalEntity();
        fresh.component = component;
        fresh.indicator = indicator;
        fresh.startedAt = at;
        fresh.source = source;
        intervals.save(fresh);
    }

    /** Ends the open interval at the validity boundary (unknown time starts there). */
    @Transactional
    public void expireOpen(String component, Instant boundary) {
        var open = intervals.findFirstByComponentAndEndedAtIsNullOrderByStartedAtDesc(component);
        if (open.isPresent() && open.get().endedAt == null
                && boundary.isAfter(open.get().startedAt)) {
            open.get().endedAt = boundary;
            intervals.save(open.get());
        }
    }

    /** Intervals overlapping [from, now) — the ongoing one capped by the caller. */
    public List<StatusInterval> overlapping(String component, Instant from, Instant now) {
        return intervals
                .findByComponentAndStartedAtLessThanEqualOrderByStartedAtAsc(component, now)
                .stream()
                .filter(entity -> entity.endedAt == null || entity.endedAt.isAfter(from))
                .map(entity -> new StatusInterval(entity.component, entity.indicator,
                        entity.startedAt, entity.endedAt))
                .toList();
    }

    /** Drops intervals that ended before the observation window (plus headroom). */
    @Transactional
    public int prune(Instant before) {
        return intervals.deleteByEndedAtLessThan(before);
    }
}
