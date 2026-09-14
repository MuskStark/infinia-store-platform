package dev.infinia.monitor.web;

import dev.infinia.monitor.config.MonitorProperties;
import dev.infinia.monitor.service.StatusEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import dev.infinia.store.contract.api.StatusDtos.ComponentDto;
import dev.infinia.store.contract.api.StatusDtos.DayDto;
import dev.infinia.store.contract.api.StatusDtos.IncidentDto;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * SSE transport for the status page's live feed ({@code GET /api/v1/status/
 * events}). Every publish gets a monotonic id and lands in an in-memory replay
 * buffer; a reconnecting browser sends {@code Last-Event-ID} (native
 * EventSource behaviour) and receives exactly the events it missed — when the
 * id is unknown (server restart) or older than the buffer, it receives a full
 * snapshot instead.
 *
 * <p>Resource bounds: at most {@code monitor.sse-max-connections} emitters, a
 * comment-only heartbeat every {@code monitor.sse-heartbeat-ms} (comments do
 * not consume event ids) whose send failure drops the client immediately, and
 * no per-client queueing — a slow or dead client is abandoned to its snapshot
 * recovery rather than buffering events in memory. All sends happen under one
 * lock: SseEmitter forbids concurrent sends, and a single choke point keeps
 * replay + live delivery race-free.</p>
 */
@Component
public class SseStatusEventBus implements StatusEventBus {

    private static final Logger log = LoggerFactory.getLogger(SseStatusEventBus.class);

    /** One buffered wire event: id, SSE event name, pre-serialized JSON data. */
    record Envelope(long id, String name, String json) {}

    private final MonitorProperties properties;
    private final ObjectMapper mapper;
    private final AtomicLong ids = new AtomicLong();
    private final ArrayDeque<Envelope> buffer = new ArrayDeque<>();
    private final CopyOnWriteArrayList<SseEmitter> clients = new CopyOnWriteArrayList<>();

    public SseStatusEventBus(MonitorProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** The connection cap guards memory; a 503 lets the page fall back to polling. */
    public boolean isFull() {
        return clients.size() >= properties.sseMaxConnections();
    }

    /** Watermark for snapshot payloads. */
    public long currentId() {
        return ids.get();
    }

    /**
     * Adds a client and settles what it missed: replay when the Last-Event-ID
     * is inside the buffer's contiguous range, a fresh snapshot otherwise
     * (new connection, server restart, or gap older than the buffer).
     */
    public synchronized void register(SseEmitter emitter, String lastEventIdHeader,
            Supplier<Object> snapshotSupplier) {
        clients.add(emitter);
        Long lastId = parse(lastEventIdHeader);
        if (lastId == null || !replayable(lastId)) {
            send(emitter, ids.get(), "snapshot", snapshotSupplier.get());
            return;
        }
        for (Envelope envelope : buffer) {
            if (envelope.id() > lastId) {
                send(emitter, envelope);
            }
        }
    }

    public synchronized void remove(SseEmitter emitter) {
        clients.remove(emitter);
    }

    private boolean replayable(long lastId) {
        return !buffer.isEmpty() && buffer.getFirst().id() <= lastId && lastId <= ids.get();
    }

    private static Long parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(header.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public synchronized void componentUpdated(ComponentDto component, String overall,
            String checkedAt) {
        publish("component.updated",
                new MonitorDtos.ComponentUpdatedEventDto(component, overall, checkedAt));
    }

    @Override
    public synchronized void incidentUpdated(IncidentDto incident) {
        publish("incident.updated", incident);
    }

    @Override
    public synchronized void historyUpdated(String componentKey, Double uptime90d,
            List<DayDto> history) {
        publish("history.updated",
                new MonitorDtos.HistoryUpdatedEventDto(componentKey, uptime90d, history));
    }

    private void publish(String name, Object payload) {
        Envelope envelope = new Envelope(ids.incrementAndGet(), name,
                mapper.writeValueAsString(payload));
        buffer.addLast(envelope);
        while (buffer.size() > properties.sseReplayCapacity()) {
            buffer.removeFirst();
        }
        for (SseEmitter emitter : clients) {
            send(emitter, envelope);
        }
    }

    private void send(SseEmitter emitter, long id, String name, Object payload) {
        send(emitter, new Envelope(id, name, mapper.writeValueAsString(payload)));
    }

    private void send(SseEmitter emitter, Envelope envelope) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(envelope.id()))
                    .name(envelope.name())
                    .data(envelope.json(), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // Dead or slow client: drop it now — it recovers via snapshot on
            // its next reconnect instead of pinning events in memory.
            log.debug("Dropping SSE client: {}", e.getMessage());
            remove(emitter);
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // already completed by the container
            }
        }
    }

    /** Comment-only keepalive: proxies see traffic, clients see no phantom events. */
    @Scheduled(fixedDelayString = "${monitor.sse-heartbeat-ms:15000}")
    public synchronized void heartbeat() {
        for (SseEmitter emitter : clients) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // already completed by the container
                }
            }
        }
    }
}
