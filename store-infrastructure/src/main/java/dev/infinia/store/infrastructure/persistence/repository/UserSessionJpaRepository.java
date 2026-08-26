package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.UserSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface UserSessionJpaRepository extends JpaRepository<UserSessionEntity, UUID> {

    List<UserSessionEntity> findByUserIdAndRevokedFalse(UUID userId);

    @Modifying
    @Query("UPDATE UserSessionEntity s SET s.revoked = true WHERE s.id = :id")
    int markRevoked(UUID id);
}
