package dev.infinia.monitor.web;

import dev.infinia.monitor.config.MonitorProperties;
import dev.infinia.monitor.service.ExternalHistory;
import dev.infinia.monitor.service.Indicators;
import dev.infinia.monitor.service.MonitorIncidentService;
import dev.infinia.monitor.service.StatusMirror;
import dev.infinia.store.contract.api.StatusDtos.ComponentDto;
import dev.infinia.store.contract.api.StatusDtos.IncidentDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The public status API served from the monitoring host — anonymous on
 * purpose, exactly like the store's own status endpoints (a status page that
 * needs a login is useless during an outage).
 */
@RestController
@RequestMapping("/api/v1/status")
public class MonitorController {

    private final StatusMirror mirror;
    private final ExternalHistory history;
    private final MonitorIncidentService incidents;
    private final MonitorProperties properties;

    public MonitorController(StatusMirror mirror, ExternalHistory history,
            MonitorIncidentService incidents, MonitorProperties properties) {
        this.mirror = mirror;
        this.history = history;
        this.incidents = incidents;
        this.properties = properties;
    }

    @GetMapping
    public MonitorDtos.MonitorStatusPageDto status() {
        Instant now = Instant.now();
        StatusMirror.Snapshot snapshot = mirror.current();

        // The external component is always live (the monitor's own freshest
        // probe); mirrored components render at their last-known indicator —
        // frozen during an outage, honestly labelled as stale.
        String external = history.liveIndicator();
        List<ComponentDto> components = new ArrayList<>();
        List<String> indicators = new ArrayList<>();
        if (snapshot != null) {
            components.addAll(snapshot.page().components());
            for (ComponentDto component : snapshot.page().components()) {
                indicators.add(component.indicator());
            }
        }
        components.add(history.component(external));
        indicators.add(external);

        boolean stale = snapshot == null
                || now.isAfter(snapshot.fetchedAt().plusMillis(properties.staleAfterMs()));
        // A cold monitor that has seen nothing must not claim all-green.
        String overall = indicators.stream().allMatch(Indicators.NO_DATA::equals)
                ? Indicators.NO_DATA
                : Indicators.worst(indicators);
        return new MonitorDtos.MonitorStatusPageDto(
                overall,
                components,
                now.toString(),
                snapshot == null ? null : snapshot.fetchedAt().toString(),
                stale);
    }

    @GetMapping("/incidents")
    public List<IncidentDto> incidents(@RequestParam(defaultValue = "50") int limit) {
        List<IncidentDto> merged = new ArrayList<>(incidents.recent(limit));
        StatusMirror.Snapshot snapshot = mirror.current();
        if (snapshot != null) {
            merged.addAll(snapshot.incidents());
        }
        // Parse, not lexical-compare: Instant.toString() precision varies, and ':' < 'Z'
        // would order a whole-minute instant above one 30 seconds later.
        merged.sort(Comparator.comparing((IncidentDto i) -> java.time.Instant.parse(
                i.startedAt())).reversed());
        return merged.stream().limit(Math.clamp(limit, 1, 200)).toList();
    }
}
