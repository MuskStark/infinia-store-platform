package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.ServiceStatusIntervalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ServiceStatusIntervalJpaRepository
        extends JpaRepository<ServiceStatusIntervalEntity, Long> {

    Optional<ServiceStatusIntervalEntity> findFirstByComponentAndEndedAtIsNullOrderByStartedAtDesc(
            String component);

    List<ServiceStatusIntervalEntity> findByComponentAndStartedAtLessThanEqualOrderByStartedAtAsc(
            String component, Instant maxStart);

    /** Housekeeping: intervals that ended before the window are useless. */
    @Transactional
    @Modifying
    long deleteByEndedAtLessThan(Instant before);
}
