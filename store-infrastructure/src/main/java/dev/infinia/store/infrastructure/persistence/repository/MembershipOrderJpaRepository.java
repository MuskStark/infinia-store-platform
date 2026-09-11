package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.MembershipOrderEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipOrderJpaRepository extends JpaRepository<MembershipOrderEntity, UUID> {

    /** Concurrent gateway callbacks for one order serialize here. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from MembershipOrderEntity o where o.orderNo = :orderNo")
    Optional<MembershipOrderEntity> findByOrderNoForUpdate(@Param("orderNo") String orderNo);

    Optional<MembershipOrderEntity> findByOrderNo(String orderNo);

    List<MembershipOrderEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<MembershipOrderEntity> findTop200ByOrderByCreatedAtDesc();

    List<MembershipOrderEntity> findByStatusAndExpiresAtBefore(String status, Instant instant);

    @Modifying
    @Query("update MembershipOrderEntity o set o.expiresAt = :expiresAt where o.id = :id")
    void updateExpiresAt(@Param("id") UUID id, @Param("expiresAt") Instant expiresAt);

    long countByStatus(String status);
}
