package dev.infinia.monitor.web;

import dev.infinia.store.contract.api.StatusDtos.ComponentDto;
import dev.infinia.store.contract.api.StatusDtos.DayDto;
import dev.infinia.store.contract.api.StatusDtos.IncidentDto;

import java.util.List;

/** Responses of the monitor's public API — the store's status shapes plus the
 *  mirror metadata the frozen-view UI needs, and the SSE event payloads. */
public final class MonitorDtos {

    private MonitorDtos() {}

    /**
     * The merged status page: the store's components at their last-known state
     * (frozen during an outage), the external-reachability component live, and
     * {@code mirroredAt}/{@code stale} describing the mirror. {@code mirroredAt}
     * is null when the monitor has never reached the store.
     */
    public record MonitorStatusPageDto(
            String indicator,
            List<ComponentDto> components,
            String checkedAt,
            String mirroredAt,
            boolean stale) {
    }

    /** SSE {@code snapshot}: the complete state, sent on connect or when replay
     *  is impossible (server restart, gap older than the replay buffer). */
    public record SnapshotEventDto(
            MonitorStatusPageDto status,
            List<IncidentDto> incidents,
            long eventId) {
    }

    /** SSE {@code component.updated}: one component's live state changed; the
     *  overall indicator is pre-computed server-side. */
    public record ComponentUpdatedEventDto(
            ComponentDto component,
            String overall,
            String checkedAt) {
    }

    /** SSE {@code history.updated}: one component's history bars changed. */
    public record HistoryUpdatedEventDto(
            String component,
            Double uptime90d,
            List<DayDto> history) {
    }
}
