package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.OutboxRecord;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.infrastructure.persistence.entity.OutboxEventEntity;
import dev.infinia.store.infrastructure.persistence.repository.OutboxJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class OutboxAdapter implements PublishingRepositories.OutboxRepository {

    private final OutboxJpaRepository jpa;

    public OutboxAdapter(OutboxJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void enqueue(OutboxRecord record) {
        OutboxEventEntity e = new OutboxEventEntity();
        e.id = record.id();
        e.aggregateType = record.aggregateType();
        e.aggregateId = record.aggregateId();
        e.type = record.type();
        e.payload = record.payloadJson();
        e.status = record.status();
        e.attempts = record.attempts();
        e.nextAttemptAt = record.nextAttemptAt();
        e.createdAt = record.createdAt();
        jpa.save(e);
    }

    @Override
    public List<OutboxRecord> findPending(int limit, Instant now) {
        return jpa.findTop50ByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(
                OutboxRecord.STATUS_PENDING, now).stream()
                .limit(limit)
                .map(OutboxAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void markDispatched(UUID id) {
        jpa.markDispatched(id);
    }

    @Override
    public void markFailed(UUID id, Instant nextAttemptAt) {
        jpa.findById(id).ifPresent(e -> {
            e.status = OutboxRecord.STATUS_FAILED;
            e.attempts++;
            e.nextAttemptAt = nextAttemptAt;
            jpa.save(e);
        });
    }

    private static OutboxRecord toDomain(OutboxEventEntity e) {
        return new OutboxRecord(e.id, e.aggregateType, e.aggregateId, e.type, e.payload, e.status,
                e.attempts, e.nextAttemptAt, e.createdAt);
    }
}
