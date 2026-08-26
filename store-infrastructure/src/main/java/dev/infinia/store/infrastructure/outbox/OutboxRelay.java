package dev.infinia.store.infrastructure.outbox;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.infinia.store.domain.model.OutboxRecord;
import dev.infinia.store.domain.model.WebhookInfo;
import dev.infinia.store.domain.port.PublishingRepositories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox relay (design §5.2): drains PENDING events after commit and
 * dispatches webhooks with HMAC-SHA256 signatures. Failures back off exponentially;
 * the relay never blocks the publishing transaction.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ATTEMPTS = 8;

    private final PublishingRepositories.OutboxRepository outbox;
    private final PublishingRepositories.WebhookRepository webhooks;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OutboxRelay(PublishingRepositories.OutboxRepository outbox,
            PublishingRepositories.WebhookRepository webhooks, ObjectMapper mapper) {
        this.outbox = outbox;
        this.webhooks = webhooks;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Scheduled(fixedDelayString = "${store.outbox.poll-interval-ms:2000}")
    @Transactional
    public void relay() {
        List<OutboxRecord> pending = outbox.findPending(50, Instant.now());
        for (OutboxRecord event : pending) {
            try {
                dispatch(event);
                outbox.markDispatched(event.id());
            } catch (Exception e) {
                log.warn("Outbox event {} dispatch failed (attempt {}): {}", event.id(),
                        event.attempts() + 1, e.getMessage());
                if (event.attempts() + 1 >= MAX_ATTEMPTS) {
                    outbox.markFailed(event.id(), Instant.now().plusSeconds(3600));
                } else {
                    long backoffSeconds = (1L << Math.min(event.attempts(), 6)) * 2;
                    outbox.markFailed(event.id(), Instant.now().plusSeconds(backoffSeconds));
                }
            }
        }
    }

    private void dispatch(OutboxRecord event) throws IOException, InterruptedException {
        // Publish/安全 events fan out to organization webhooks; other event types are
        // consumed by the search indexer / mailer in later phases and are simply drained.
        if (!event.type().startsWith("release.")) {
            return;
        }
        JsonNode payload = mapper.readTree(event.payloadJson());
        JsonNode coordinate = payload.get("coordinate");
        if (coordinate == null) {
            return;
        }
        // The listing's namespace determines which organization's webhooks to call.
        String namespace = namespaceOf(coordinate.asText());
        if (namespace == null) {
            return;
        }
        // Webhooks are registered per organization; resolve via the organization that
        // owns the namespace by looking up hooks attached to the event's organization id.
        UUID organizationId = payload.has("organizationId")
                ? UUID.fromString(payload.get("organizationId").asText())
                : null;
        if (organizationId == null) {
            return;
        }
        for (WebhookInfo hook : webhooks.findActiveByEventAndOrganization(event.type(), organizationId)) {
            deliver(hook, event);
        }
    }

    private void deliver(WebhookInfo hook, OutboxRecord event)
            throws IOException, InterruptedException {
        String body = mapper.createObjectNode()
                .put("eventType", event.type())
                .put("eventId", event.id().toString())
                .put("occurredAt", event.createdAt().toString())
                .set("payload", mapper.readTree(event.payloadJson()))
                .toString();
        String signature = hmacSha256(hook.secret(), body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(hook.url()))
                .header("Content-Type", "application/json")
                .header("X-Infinia-Event", event.type())
                .header("X-Infinia-Signature", "sha256=" + signature)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("Webhook " + hook.url() + " responded " + response.statusCode());
        }
    }

    private static String namespaceOf(String coordinate) {
        // infinia://<type>/<namespace>/<slug>[@version]
        String[] parts = coordinate.replace("infinia://", "").split("/");
        return parts.length >= 2 ? parts[1] : null;
    }

    private static String hmacSha256(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
