package dev.infinia.monitor.service;

import dev.infinia.store.contract.api.StatusDtos.ComponentDto;
import dev.infinia.store.contract.api.StatusDtos.DayDto;
import dev.infinia.store.contract.api.StatusDtos.IncidentDto;

import java.util.List;

/**
 * Push transport for the status page's live feed. The probing/state layer calls
 * these after every meaningful change; the transport (SSE, phase 2) turns them
 * into {@code component.updated} / {@code incident.updated} /
 * {@code history.updated} events. Keeping it an interface lets the probing
 * refactor land before the stream exists.
 */
public interface StatusEventBus {

    /**
     * One component's live state changed (confirmed transition, pending flag
     * raised/cleared, or a stale flip on the mirrored side). {@code overall} is
     * the server-computed page indicator after the change.
     */
    void componentUpdated(ComponentDto component, String overall, String checkedAt);

    /** An incident was created, changed severity, or resolved. */
    void incidentUpdated(IncidentDto incident);

    /** A component's history bars changed (per-minute rollup or a transition). */
    void historyUpdated(String componentKey, Double uptime90d, List<DayDto> history);
}
