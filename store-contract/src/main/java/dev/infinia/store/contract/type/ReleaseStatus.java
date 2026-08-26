package dev.infinia.store.contract.type;

/**
 * Publishing state machine (design §8.1).
 *
 * <pre>
 * DRAFT -> UPLOADING -> SCANNING -> REJECTED | IN_REVIEW
 * IN_REVIEW -> CHANGES_REQUESTED -> DRAFT | APPROVED
 * APPROVED -> PUBLISHED -> DEPRECATED | YANKED | QUARANTINED
 * QUARANTINED -> PUBLISHED (false positive cleared)
 * </pre>
 */
public enum ReleaseStatus {
    DRAFT,
    UPLOADING,
    SCANNING,
    REJECTED,
    IN_REVIEW,
    CHANGES_REQUESTED,
    APPROVED,
    PUBLISHED,
    DEPRECATED,
    YANKED,
    QUARANTINED
}
