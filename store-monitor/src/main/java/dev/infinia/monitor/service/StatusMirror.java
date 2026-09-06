package dev.infinia.monitor.service;

import dev.infinia.store.contract.api.StatusDtos;
import dev.infinia.monitor.config.MonitorProperties;
import dev.infinia.monitor.persistence.MirrorSnapshotEntity;
import dev.infinia.monitor.persistence.MirrorSnapshotRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mirrors the store's public status API. On every poll the store's full status
 * page — including each component's complete 90-day history — is fetched and
 * kept in memory and on disk. When the store is unreachable the last snapshot
 * stays frozen and keeps rendering: the history remains complete up to the
 * outage, and the external-reachability component tells the rest of the story.
 */
@Component
public class StatusMirror {

    private static final Logger log = LoggerFactory.getLogger(StatusMirror.class);

    /** The immutable last-known state; null until the store has been seen once. */
    public record Snapshot(Instant fetchedAt, StatusDtos.StatusPageDto page,
            List<StatusDtos.IncidentDto> incidents) {}

    private final MonitorProperties properties;
    private final MirrorSnapshotRepository snapshots;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    public StatusMirror(MonitorProperties properties, MirrorSnapshotRepository snapshots,
            ObjectMapper mapper) {
        this.properties = properties;
        this.snapshots = snapshots;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.probeTimeoutMs()))
                .build();
    }

    /** A monitor restart during a store outage still renders the last snapshot. */
    @PostConstruct
    public void restore() {
        snapshots.findById(1).ifPresent(stored -> {
            try {
                current.set(new Snapshot(stored.fetchedAt,
                        mapper.readValue(stored.pageJson, StatusDtos.StatusPageDto.class),
                        mapper.readValue(stored.incidentsJson,
                                new TypeReference<List<StatusDtos.IncidentDto>>() {})));
            } catch (Exception e) {
                log.warn("Persisted mirror snapshot is unreadable; starting cold: {}", e.getMessage());
            }
        });
    }

    /** Fetches both endpoints; any failure returns empty and keeps the old snapshot. */
    public Optional<Snapshot> fetch() {
        try {
            StatusDtos.StatusPageDto page = get(StatusDtos.StatusPageDto.class, "/api/v1/status");
            List<StatusDtos.IncidentDto> incidents = getIncidents();
            Snapshot snapshot = new Snapshot(Instant.now(), page, incidents);
            update(snapshot);
            return Optional.of(snapshot);
        } catch (Exception e) {
            log.debug("Mirror fetch failed (store unreachable?): {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Snapshot current() {
        return current.get();
    }

    private void update(Snapshot snapshot) {
        current.set(snapshot);
        MirrorSnapshotEntity stored = snapshots.findById(1)
                .orElseGet(MirrorSnapshotEntity::new);
        stored.id = 1;
        stored.fetchedAt = snapshot.fetchedAt();
        stored.pageJson = mapper.writeValueAsString(snapshot.page());
        stored.incidentsJson = mapper.writeValueAsString(snapshot.incidents());
        snapshots.save(stored);
    }

    private <T> T get(Class<T> type, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(target(path))
                .timeout(Duration.ofMillis(properties.probeTimeoutMs()))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(path + " answered " + response.statusCode());
        }
        return mapper.readValue(response.body(), type);
    }

    private List<StatusDtos.IncidentDto> getIncidents() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(target("/api/v1/status/incidents"))
                .timeout(Duration.ofMillis(properties.probeTimeoutMs()))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("incidents answered " + response.statusCode());
        }
        return mapper.readValue(response.body(),
                mapper.getTypeFactory().constructCollectionType(List.class,
                        StatusDtos.IncidentDto.class));
    }

    private URI target(String path) {
        String base = properties.targetBaseUrl().toString().replaceAll("/+$", "");
        return URI.create(base + path);
    }
}
