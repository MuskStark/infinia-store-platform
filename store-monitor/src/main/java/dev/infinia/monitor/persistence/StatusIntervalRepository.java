package dev.infinia.monitor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StatusIntervalRepository extends JpaRepository<StatusIntervalEntity, Long> {

    Optional<StatusIntervalEntity> findFirstByComponentAndEndedAtIsNullOrderByStartedAtDesc(
            String component);

    List<StatusIntervalEntity> findByComponentAndStartedAtLessThanEqualOrderByStartedAtAsc(
            String component, Instant maxStart);

    @Transactional
    @Modifying
    int deleteByEndedAtLessThan(Instant before);
}
