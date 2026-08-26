package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.AuditRecord;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.infrastructure.persistence.entity.AuditEventEntity;
import dev.infinia.store.infrastructure.persistence.repository.AuditEventJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AuditAdapter implements PublishingRepositories.AuditEventRepository {

    private final AuditEventJpaRepository jpa;

    public AuditAdapter(AuditEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void append(AuditRecord record) {
        AuditEventEntity e = new AuditEventEntity();
        e.id = record.id();
        e.actorType = record.actorType();
        e.actorId = record.actorId();
        e.action = record.action();
        e.resourceType = record.resourceType();
        e.resourceId = record.resourceId();
        e.beforeSummary = record.beforeSummary();
        e.afterSummary = record.afterSummary();
        e.ipHash = record.ipHash();
        e.traceId = record.traceId();
        e.occurredAt = record.occurredAt();
        jpa.save(e);
    }

    @Override
    public List<AuditRecord> findRecent(int limit, String resourceType) {
        List<AuditEventEntity> rows = resourceType == null
                ? jpa.findTop200ByOrderByOccurredAtDesc()
                : jpa.findTop200ByResourceTypeOrderByOccurredAtDesc(resourceType);
        return rows.stream().limit(limit)
                .map(e -> new AuditRecord(e.id, e.actorType, e.actorId, e.action, e.resourceType,
                        e.resourceId, e.beforeSummary, e.afterSummary, e.ipHash, e.traceId,
                        e.occurredAt))
                .toList();
    }
}
