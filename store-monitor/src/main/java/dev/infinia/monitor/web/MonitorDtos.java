package dev.infinia.monitor.web;

import dev.infinia.store.contract.api.StatusDtos.ComponentDto;

import java.util.List;

/** Responses of the monitor's public API — the store's status shapes plus the
 *  mirror metadata the frozen-view UI needs. */
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
}
