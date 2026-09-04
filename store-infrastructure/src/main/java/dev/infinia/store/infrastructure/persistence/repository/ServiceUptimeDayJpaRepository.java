package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.ServiceUptimeDayEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ServiceUptimeDayJpaRepository
        extends JpaRepository<ServiceUptimeDayEntity, ServiceUptimeDayEntity.Key> {

    List<ServiceUptimeDayEntity> findByComponentAndDayGreaterThanEqual(String component,
            LocalDate from);

    /** Housekeeping: buckets older than the observation window are useless. */
    long deleteByDayBefore(LocalDate day);
}
