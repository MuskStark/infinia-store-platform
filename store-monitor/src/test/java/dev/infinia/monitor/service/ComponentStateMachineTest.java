package dev.infinia.monitor.service;

import dev.infinia.store.contract.status.ComponentStateMachine;
import dev.infinia.store.contract.status.ComponentStateMachine.Kind;
import dev.infinia.store.contract.status.ComponentStateMachine.State;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirmation semantics shared by the monitor's external prober and the
 * store's own samplers: first observation confirms immediately, a fault needs
 * a streak of failures, recovery a streak of successes, and the first
 * conflicting observation raises 确认中 (pending) without flipping the page.
 */
class ComponentStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-09-14T08:00:00Z");

    @Test
    void firstRealObservationConfirmsImmediately() {
        var machine = new ComponentStateMachine(2, 2);
        var result = machine.observe("major_outage", T0);
        assertEquals(Kind.CONFIRMED, result.kind());
        assertEquals("major_outage", result.state().indicator());
        assertFalse(result.state().pending());
        assertEquals(T0, result.state().observedAt());
        assertNull(result.state().lastSuccessAt());
    }

    @Test
    void firstNoDataObservationStaysUnconfirmed() {
        var machine = new ComponentStateMachine(2, 2);
        var result = machine.observe("no_data", T0);
        assertEquals(Kind.STEADY, result.kind());
        assertNull(result.state().indicator());
        assertEquals("no_data", result.state().indicatorOrNoData());
    }

    @Test
    void singleFailureIsPendingThenStreakConfirms() {
        var machine = new ComponentStateMachine(2, 2);
        machine.observe("operational", T0);

        var first = machine.observe("major_outage", T0.plusSeconds(5));
        assertEquals(Kind.PENDING, first.kind());
        assertEquals("operational", first.state().indicator(), "one failure must not flip the page");
        assertTrue(first.state().pending());

        var second = machine.observe("major_outage", T0.plusSeconds(10));
        assertEquals(Kind.CONFIRMED, second.kind());
        assertEquals("major_outage", second.state().indicator());
        assertFalse(second.state().pending());
    }

    @Test
    void recoveryNeedsItsOwnStreak() {
        var machine = new ComponentStateMachine(2, 2);
        machine.observe("major_outage", T0);
        machine.observe("major_outage", T0.plusSeconds(5));

        var first = machine.observe("operational", T0.plusSeconds(10));
        assertEquals(Kind.PENDING, first.kind());
        assertEquals("major_outage", first.state().indicator(), "one success must not claim recovery");
        assertTrue(first.state().pending());

        var second = machine.observe("operational", T0.plusSeconds(15));
        assertEquals(Kind.CONFIRMED, second.kind());
        assertEquals("operational", second.state().indicator());
        assertEquals(T0.plusSeconds(15), second.state().lastSuccessAt());
    }

    @Test
    void blipPassesAndClearsPending() {
        var machine = new ComponentStateMachine(2, 2);
        machine.observe("operational", T0);

        assertEquals(Kind.PENDING, machine.observe("degraded", T0.plusSeconds(5)).kind());
        var cleared = machine.observe("operational", T0.plusSeconds(10));
        assertEquals(Kind.PENDING_CLEARED, cleared.kind());
        assertFalse(cleared.state().pending());
        assertEquals("operational", cleared.state().indicator());
    }

    @Test
    void severityShiftBetweenFailureStatesAlsoConfirms() {
        var machine = new ComponentStateMachine(2, 2);
        machine.observe("partial_outage", T0);
        assertEquals(Kind.PENDING, machine.observe("major_outage", T0.plusSeconds(5)).kind());
        var shifted = machine.observe("major_outage", T0.plusSeconds(10));
        assertEquals(Kind.CONFIRMED, shifted.kind());
        assertEquals("major_outage", shifted.state().indicator());
    }

    @Test
    void conflictingObservationsDoNotAccumulateAcrossDirections() {
        var machine = new ComponentStateMachine(2, 2);
        machine.observe("operational", T0);
        machine.observe("major_outage", T0.plusSeconds(5));   // pending, streak 1
        assertEquals(Kind.PENDING_CLEARED, machine.observe("operational", T0.plusSeconds(10)).kind());
        // The streak reset means this fresh failure starts counting from one again.
        assertEquals(Kind.PENDING, machine.observe("major_outage", T0.plusSeconds(15)).kind());
    }

    @Test
    void stateReadableFromOtherThreadsReflectsConfirmedView() {
        var machine = new ComponentStateMachine(2, 2);
        machine.observe("operational", T0);
        machine.observe("major_outage", T0.plusSeconds(5));
        State view = machine.state();
        assertEquals("operational", view.indicator());
        assertTrue(view.pending());
    }

    @Test
    void unknownIndicatorIsRejected() {
        var machine = new ComponentStateMachine(2, 2);
        assertThrows(IllegalArgumentException.class, () -> machine.observe("unknown", T0));
        assertThrows(IllegalArgumentException.class, () -> machine.observe(null, T0));
    }

    @Test
    void thresholdsBelowOneAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ComponentStateMachine(0, 2));
        assertThrows(IllegalArgumentException.class, () -> new ComponentStateMachine(2, 0));
    }

    @Test
    void customThresholdsAreHonoured() {
        var machine = new ComponentStateMachine(3, 1);
        machine.observe("operational", T0);
        assertEquals(Kind.PENDING, machine.observe("degraded", T0.plusSeconds(5)).kind());
        assertEquals(Kind.PENDING, machine.observe("degraded", T0.plusSeconds(10)).kind());
        assertEquals(Kind.CONFIRMED, machine.observe("degraded", T0.plusSeconds(15)).kind());
        assertEquals(Kind.CONFIRMED, machine.observe("operational", T0.plusSeconds(20)).kind());
    }
}
