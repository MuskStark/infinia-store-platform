package dev.infinia.store.domain.service;

import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.DomainException;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Publishing state machine (design §8.1). Enforces every legal transition;
 * published releases are never deleted, only withdrawn (YANKED / QUARANTINED /
 * DEPRECATED) and quarantined releases can be restored only explicitly.
 */
public final class ReleaseStateMachine {

    private static final Map<ReleaseStatus, Set<ReleaseStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(ReleaseStatus.DRAFT, EnumSet.of(ReleaseStatus.UPLOADING)),
            Map.entry(ReleaseStatus.UPLOADING, EnumSet.of(ReleaseStatus.SCANNING)),
            Map.entry(ReleaseStatus.SCANNING, EnumSet.of(ReleaseStatus.REJECTED, ReleaseStatus.IN_REVIEW)),
            Map.entry(ReleaseStatus.REJECTED, EnumSet.of(ReleaseStatus.DRAFT)),
            // IN_REVIEW -> REJECTED implements the reviewer's "reject" decision (design §7.3);
            // SCANNING -> REJECTED is the automatic policy rejection (design §8.1).
            Map.entry(ReleaseStatus.IN_REVIEW, EnumSet.of(ReleaseStatus.APPROVED,
                    ReleaseStatus.REJECTED, ReleaseStatus.CHANGES_REQUESTED)),
            Map.entry(ReleaseStatus.CHANGES_REQUESTED, EnumSet.of(ReleaseStatus.DRAFT)),
            Map.entry(ReleaseStatus.APPROVED, EnumSet.of(ReleaseStatus.PUBLISHED)),
            Map.entry(ReleaseStatus.PUBLISHED, EnumSet.of(ReleaseStatus.DEPRECATED,
                    ReleaseStatus.YANKED, ReleaseStatus.QUARANTINED)),
            Map.entry(ReleaseStatus.DEPRECATED, EnumSet.of(ReleaseStatus.PUBLISHED)),
            Map.entry(ReleaseStatus.YANKED, EnumSet.of(ReleaseStatus.PUBLISHED)),
            Map.entry(ReleaseStatus.QUARANTINED, EnumSet.of(ReleaseStatus.PUBLISHED)));

    private ReleaseStateMachine() {}

    public static void assertTransition(ReleaseStatus from, ReleaseStatus to) {
        Set<ReleaseStatus> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new DomainException(StoreErrorCode.INVALID_STATE_TRANSITION,
                    "Illegal release transition " + from + " -> " + to,
                    Map.of("from", from.name(), "to", to.name()));
        }
    }

    public static boolean canTransition(ReleaseStatus from, ReleaseStatus to) {
        Set<ReleaseStatus> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }
}
