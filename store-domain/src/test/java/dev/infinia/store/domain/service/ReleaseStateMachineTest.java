package dev.infinia.store.domain.service;

import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.DomainException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseStateMachineTest {

    @Test
    void allowsHappyPathToPublished() {
        assertDoesNotThrow(() -> ReleaseStateMachine.assertTransition(ReleaseStatus.DRAFT, ReleaseStatus.UPLOADING));
        assertDoesNotThrow(() -> ReleaseStateMachine.assertTransition(ReleaseStatus.UPLOADING, ReleaseStatus.SCANNING));
        assertDoesNotThrow(() -> ReleaseStateMachine.assertTransition(ReleaseStatus.SCANNING, ReleaseStatus.IN_REVIEW));
        assertDoesNotThrow(() -> ReleaseStateMachine.assertTransition(ReleaseStatus.IN_REVIEW, ReleaseStatus.APPROVED));
        assertDoesNotThrow(() -> ReleaseStateMachine.assertTransition(ReleaseStatus.APPROVED, ReleaseStatus.PUBLISHED));
    }

    @Test
    void allowsWithdrawalsAndRestore() {
        assertTrue(ReleaseStateMachine.canTransition(ReleaseStatus.PUBLISHED, ReleaseStatus.YANKED));
        assertTrue(ReleaseStateMachine.canTransition(ReleaseStatus.PUBLISHED, ReleaseStatus.QUARANTINED));
        assertTrue(ReleaseStateMachine.canTransition(ReleaseStatus.PUBLISHED, ReleaseStatus.DEPRECATED));
        assertTrue(ReleaseStateMachine.canTransition(ReleaseStatus.QUARANTINED, ReleaseStatus.PUBLISHED));
        assertTrue(ReleaseStateMachine.canTransition(ReleaseStatus.YANKED, ReleaseStatus.PUBLISHED));
    }

    @Test
    void rejectsSkipsAndIllegalPaths() {
        assertThrows(DomainException.class,
                () -> ReleaseStateMachine.assertTransition(ReleaseStatus.DRAFT, ReleaseStatus.PUBLISHED));
        assertThrows(DomainException.class,
                () -> ReleaseStateMachine.assertTransition(ReleaseStatus.SCANNING, ReleaseStatus.APPROVED));
        assertThrows(DomainException.class,
                () -> ReleaseStateMachine.assertTransition(ReleaseStatus.REJECTED, ReleaseStatus.PUBLISHED));
        assertThrows(DomainException.class,
                () -> ReleaseStateMachine.assertTransition(ReleaseStatus.CHANGES_REQUESTED, ReleaseStatus.PUBLISHED));
    }

    @Test
    void exceptionCarriesStableCode() {
        DomainException e = assertThrows(DomainException.class,
                () -> ReleaseStateMachine.assertTransition(ReleaseStatus.DRAFT, ReleaseStatus.PUBLISHED));
        assertEquals(StoreErrorCode.INVALID_STATE_TRANSITION, e.code);
        assertEquals("DRAFT", e.params.get("from"));
        assertEquals("PUBLISHED", e.params.get("to"));
    }
}
