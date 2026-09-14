package dev.infinia.store.contract.status;

import dev.infinia.store.contract.api.StatusDtos;

import java.time.Instant;

/**
 * Confirmation state machine for one status component: a single bad probe must
 * not flip the page (确认中 first, confirmed after a streak), and recovery is
 * equally deliberate. Shared by the store's in-process samplers and the
 * standalone monitor's external prober so both halves confirm alike.
 *
 * <p>Rules: the first real observation confirms immediately (one data point is
 * an honest verdict); afterwards a streak of {@code failureThreshold}
 * conflicting non-operational observations confirms a fault, a streak of
 * {@code recoveryThreshold} operational observations confirms recovery, and the
 * first conflicting observation alone raises {@code pending} so the page can
 * say 确认中 without pretending everything is fine.</p>
 */
public final class ComponentStateMachine {

    /** The observable state of one component; {@code indicator} is null before any observation. */
    public record State(String indicator, boolean pending, Instant observedAt, Instant lastSuccessAt) {

        public String indicatorOrNoData() {
            return indicator == null ? StatusDtos.NO_DATA : indicator;
        }
    }

    /** What one observation did to the confirmed state. */
    public enum Kind {
        /** The confirmed indicator changed (includes the very first observation). */
        CONFIRMED,
        /** A conflicting observation is awaiting its confirmation streak. */
        PENDING,
        /** The pending flag cleared without a transition (the blip passed). */
        PENDING_CLEARED,
        /** Nothing of interest changed. */
        STEADY
    }

    public record Result(Kind kind, State state) {}

    private final int failureThreshold;
    private final int recoveryThreshold;

    private State state = new State(null, false, null, null);
    private int streak;

    public ComponentStateMachine(int failureThreshold, int recoveryThreshold) {
        if (failureThreshold < 1 || recoveryThreshold < 1) {
            throw new IllegalArgumentException("Confirmation thresholds must be >= 1");
        }
        this.failureThreshold = failureThreshold;
        this.recoveryThreshold = recoveryThreshold;
    }

    /** Feeds one raw probe outcome; returns what happened to the confirmed state. */
    public synchronized Result observe(String indicator, Instant at) {
        if (indicator == null
                || !(indicator.equals(StatusDtos.OPERATIONAL) || indicator.equals(StatusDtos.DEGRADED)
                        || indicator.equals(StatusDtos.PARTIAL_OUTAGE)
                        || indicator.equals(StatusDtos.MAJOR_OUTAGE)
                        || indicator.equals(StatusDtos.NO_DATA))) {
            throw new IllegalArgumentException("Unknown status indicator: " + indicator);
        }
        boolean success = StatusDtos.OPERATIONAL.equals(indicator);
        boolean wasPending = state.pending;
        Instant lastSuccess = success ? at : state.lastSuccessAt;
        String confirmed = state.indicator;

        if (confirmed == null) {
            // No valid observation yet: no_data stays unconfirmed, anything real
            // is the honest first verdict.
            State next = StatusDtos.NO_DATA.equals(indicator)
                    ? new State(null, false, at, null)
                    : new State(indicator, false, at, lastSuccess);
            state = next;
            streak = 0;
            return new Result(next.indicator() == null ? Kind.STEADY : Kind.CONFIRMED, next);
        }

        if (indicator.equals(confirmed)) {
            streak = 0;
            State next = new State(confirmed, false, at, lastSuccess);
            state = next;
            return new Result(wasPending ? Kind.PENDING_CLEARED : Kind.STEADY, next);
        }

        streak++;
        int threshold = success ? recoveryThreshold : failureThreshold;
        if (streak >= threshold) {
            State next = new State(indicator, false, at, lastSuccess);
            state = next;
            streak = 0;
            return new Result(Kind.CONFIRMED, next);
        }
        State next = new State(confirmed, true, at, lastSuccess);
        state = next;
        return new Result(Kind.PENDING, next);
    }

    /** The current confirmed state; safe from any thread. */
    public synchronized State state() {
        return state;
    }
}
