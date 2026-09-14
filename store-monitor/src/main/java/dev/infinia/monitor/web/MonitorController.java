package dev.infinia.monitor.web;

import dev.infinia.monitor.config.MonitorProperties;
import dev.infinia.monitor.service.ExternalHistory;
import dev.infinia.monitor.service.Indicators;
import dev.infinia.monitor.service.MonitorIncidentService;
import dev.infinia.monitor.service.StatusMirror;
import dev.infinia.store.contract.api.StatusDtos.ComponentDto;
import dev.infinia.store.contract.api.StatusDtos.IncidentDto;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final SseStatusEventBus events;

    public MonitorController(StatusMirror mirror, ExternalHistory history,
            MonitorIncidentService incidents, MonitorProperties properties,
            SseStatusEventBus events) {
        this.mirror = mirror;
        this.history = history;
        this.incidents = incidents;
        this.properties = properties;
        this.events = events;
    }

    @GetMapping
    public MonitorDtos.MonitorStatusPageDto status() {
        Instant now = Instant.now();
        StatusMirror.Snapshot snapshot = mirror.current();

        // The external component is always live (the monitor's own freshest
        // confirmed state); mirrored components render at their last-known
        // indicator — frozen during an outage, honestly labelled as stale.
        List<ComponentDto> components = new ArrayList<>();
        List<String> indicators = new ArrayList<>();
        boolean mirrorStale = snapshot == null
                || now.isAfter(snapshot.fetchedAt().plusMillis(properties.staleAfterMs()));
        if (snapshot != null) {
            for (ComponentDto component : snapshot.page().components()) {
                components.add(mirrorStale ? withStale(component) : component);
                indicators.add(component.indicator());
            }
        }
        ComponentDto external = history.component();
        components.add(external);
        indicators.add(external.indicator());
        // A cold monitor that has seen nothing must not claim all-green.
        String overall = indicators.stream().allMatch(Indicators.NO_DATA::equals)
                ? Indicators.NO_DATA
                : Indicators.worst(indicators);
        return new MonitorDtos.MonitorStatusPageDto(
                overall,
                components,
                now.toString(),
                snapshot == null ? null : snapshot.fetchedAt().toString(),
                mirrorStale);
    }

    /** A frozen mirrored component must wear its staleness on the cell itself. */
    static ComponentDto withStale(ComponentDto component) {
        return new ComponentDto(component.key(), component.indicator(), component.uptime90d(),
                component.history(), component.observedAt(), component.lastSuccessAt(),
                component.pending(), true);
    }

    /**
     * The live event stream: {@code snapshot} on connect (or when the
     * Last-Event-ID predates the replay buffer), then
     * {@code component.updated} / {@code incident.updated} /
     * {@code history.updated} as confirmed changes land. Comment-only
     * heartbeats keep proxies from reaping the connection.
     *
     * <p>The resume point is accepted from the native {@code Last-Event-ID}
     * header or a {@code lastEventId} query parameter — a browser EventSource
     * only resends the header on its own automatic retry, so clients that
     * manage reconnection themselves (our backoff wrapper) pass it as a query
     * parameter on the fresh connection.</p>
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
            @RequestParam(value = "lastEventId", required = false) String lastEventIdParam,
            HttpServletResponse response) {
        if (events.isFull()) {
            // The page falls back to low-frequency polling when handed a 503.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "event stream connection cap reached");
        }
        String lastEventId = lastEventIdHeader != null && !lastEventIdHeader.isBlank()
                ? lastEventIdHeader : lastEventIdParam;
        // nginx-family proxies (SafeLine) buffer upstream responses by default;
        // opt out explicitly, and keep intermediaries from caching the stream.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(0L); // heartbeats detect dead clients
        emitter.onCompletion(() -> events.remove(emitter));
        emitter.onTimeout(() -> events.remove(emitter));
        this.events.register(emitter, lastEventId, () -> new MonitorDtos.SnapshotEventDto(
                status(), incidents(50), this.events.currentId()));
        return emitter;
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
