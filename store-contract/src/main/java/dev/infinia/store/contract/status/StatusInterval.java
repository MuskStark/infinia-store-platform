package dev.infinia.store.contract.status;

import dev.infinia.store.contract.api.StatusDtos;

import java.time.Instant;

/**
 * One recorded stretch of a component's confirmed state: from
 * {@code startedAt} (inclusive) to {@code endedAt} (exclusive; null while the
 * state is ongoing). Gaps between intervals are unknown time — neither
 * available nor unavailable — which is what the coverage metric discloses.
 */
public record StatusInterval(String component, String indicator, Instant startedAt, Instant endedAt) {

    public StatusInterval {
        if (component == null || component.isBlank()) {
            throw new IllegalArgumentException("component must not be blank");
        }
        if (indicator == null
                || !(indicator.equals(StatusDtos.OPERATIONAL) || indicator.equals(StatusDtos.DEGRADED)
                        || indicator.equals(StatusDtos.PARTIAL_OUTAGE)
                        || indicator.equals(StatusDtos.MAJOR_OUTAGE))) {
            throw new IllegalArgumentException("Unknown status indicator: " + indicator);
        }
        if (startedAt == null || (endedAt != null && !endedAt.isAfter(startedAt))) {
            throw new IllegalArgumentException("interval must have a positive duration");
        }
    }
}
