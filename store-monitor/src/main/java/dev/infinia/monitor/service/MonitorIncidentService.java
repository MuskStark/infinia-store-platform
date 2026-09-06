package dev.infinia.monitor.service;

import dev.infinia.monitor.persistence.MonitorIncidentEntity;
import dev.infinia.monitor.persistence.MonitorIncidentRepository;
import dev.infinia.store.contract.api.StatusDtos.IncidentDto;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Auto lifecycle for the incidents the monitor owns — the store's external
 * reachability. A failing probe opens one incident, recovery resolves it with
 * the measured duration; same self-managing contract as the store's own
 * incident tracker, applied to the one failure the store cannot see itself.
 */
@Component
public class MonitorIncidentService {

    private static final String DISPLAY_NAME = "External reachability";

    private final MonitorIncidentRepository incidents;

    public MonitorIncidentService(MonitorIncidentRepository incidents) {
        this.incidents = incidents;
    }

    /** Called once per poll with the external component's live indicator. */
    public void track(String indicator, Instant now) {
        boolean outage = Indicators.rank(indicator) >= 2;
        boolean degraded = Indicators.DEGRADED.equals(indicator);
        var existing = incidents
                .findFirstByComponentAndStatusOrderByStartedAtDesc(
                        ExternalHistory.COMPONENT_KEY, "investigating");
        if (outage || degraded) {
            String impact = outage ? "outage" : "degraded";
            if (existing.isPresent()) {
                MonitorIncidentEntity open = existing.orElseThrow();
                open.impact = impact;
                open.updatedAt = now;
                incidents.save(open);
            } else {
                MonitorIncidentEntity fresh = new MonitorIncidentEntity();
                fresh.id = UUID.randomUUID();
                fresh.component = ExternalHistory.COMPONENT_KEY;
                fresh.title = DISPLAY_NAME
                        + (outage ? " is unavailable" : " is degraded");
                fresh.impact = impact;
                fresh.status = "investigating";
                fresh.startedAt = now;
                fresh.updatedAt = now;
                incidents.save(fresh);
            }
        } else if (existing.isPresent()) {
            MonitorIncidentEntity open = existing.orElseThrow();
            open.status = "resolved";
            open.resolvedAt = now;
            open.updatedAt = now;
            incidents.save(open);
        }
        incidents.flush();
    }

    public List<IncidentDto> recent(int limit) {
        return incidents.findTop50ByOrderByStartedAtDesc().stream()
                .map(entity -> new IncidentDto(entity.id.toString(), entity.component,
                        entity.title, entity.impact, entity.status,
                        entity.startedAt.toString(),
                        entity.resolvedAt == null ? null : entity.resolvedAt.toString(),
                        entity.updatedAt.toString()))
                .limit(Math.clamp(limit, 1, 200))
                .toList();
    }
}
