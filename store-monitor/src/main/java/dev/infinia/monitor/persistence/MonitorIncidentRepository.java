package dev.infinia.monitor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonitorIncidentRepository extends JpaRepository<MonitorIncidentEntity, UUID> {

    Optional<MonitorIncidentEntity> findFirstByComponentAndStatusOrderByStartedAtDesc(
            String component, String status);

    List<MonitorIncidentEntity> findTop50ByOrderByStartedAtDesc();
}
