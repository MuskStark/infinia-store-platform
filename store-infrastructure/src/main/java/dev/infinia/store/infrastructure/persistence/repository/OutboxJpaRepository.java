package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findTop50ByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(
            String status, Instant now);

    @Modifying
    @Query("UPDATE OutboxEventEntity o SET o.status = 'DISPATCHED' WHERE o.id = :id")
    int markDispatched(UUID id);
}
