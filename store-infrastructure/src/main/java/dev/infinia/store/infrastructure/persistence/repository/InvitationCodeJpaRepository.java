package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.InvitationCodeEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationCodeJpaRepository extends JpaRepository<InvitationCodeEntity, UUID> {

    /** Concurrent registrations for one code serialize here. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from InvitationCodeEntity c where c.code = :code")
    Optional<InvitationCodeEntity> findByCodeForUpdate(@Param("code") String code);

    Optional<InvitationCodeEntity> findByCode(String code);

    List<InvitationCodeEntity> findByCreatedByOrderByCreatedAtDesc(UUID createdBy);

    long countByCreatedByAndCreatedAtGreaterThanEqual(UUID createdBy, Instant since);

    List<InvitationCodeEntity> findTop200ByOrderByCreatedAtDesc();
}
