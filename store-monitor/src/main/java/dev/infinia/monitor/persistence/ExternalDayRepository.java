package dev.infinia.monitor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ExternalDayRepository
        extends JpaRepository<ExternalDayEntity, ExternalDayEntity.Key> {

    List<ExternalDayEntity> findByComponentAndDayGreaterThanEqual(String component, LocalDate from);

    /** Housekeeping: buckets older than the observation window are useless. */
    long deleteByDayBefore(LocalDate day);
}
