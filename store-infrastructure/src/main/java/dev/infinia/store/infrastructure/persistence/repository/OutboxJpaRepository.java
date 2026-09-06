package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * PENDING rows plus FAILED rows whose backoff has elapsed — a FAILED event
     * that never comes back here would be silently dropped, defeating the
     * retry half of the dispatch index.
     */
    @Query("SELECT o FROM OutboxEventEntity o WHERE o.status IN ('PENDING', 'FAILED')"
            + " AND o.nextAttemptAt <= :now ORDER BY o.createdAt ASC")
    List<OutboxEventEntity> findRetryable(@Param("now") Instant now);

    @Modifying
    @Query("UPDATE OutboxEventEntity o SET o.status = 'DISPATCHED' WHERE o.id = :id")
    int markDispatched(UUID id);

    @Modifying
    @Query("UPDATE OutboxEventEntity o SET o.status = 'DEAD' WHERE o.id = :id")
    int markDead(UUID id);
}
