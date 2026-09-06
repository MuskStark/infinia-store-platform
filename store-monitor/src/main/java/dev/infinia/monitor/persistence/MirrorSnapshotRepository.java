package dev.infinia.monitor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MirrorSnapshotRepository extends JpaRepository<MirrorSnapshotEntity, Integer> {
}
