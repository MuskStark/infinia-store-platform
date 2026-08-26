package dev.infinia.store.app.service;

import dev.infinia.store.domain.model.AuditRecord;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.service.UuidV7;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Appends non-repudiable audit events (design §14.3). */
@Service
public class AuditService {

    private final PublishingRepositories.AuditEventRepository auditEvents;

    public AuditService(PublishingRepositories.AuditEventRepository auditEvents) {
        this.auditEvents = auditEvents;
    }

    public void record(String actorType, String actorId, String action, String resourceType,
            String resourceId, String before, String after, String traceId) {
        auditEvents.append(new AuditRecord(UuidV7.generate(), actorType, actorId, action,
                resourceType, resourceId, before, after, null, traceId, Instant.now()));
    }
}
