package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, String> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    List<RefreshTokenEntity> findBySessionId(UUID sessionId);

    List<RefreshTokenEntity> findBySessionIdAndRevokedFalse(UUID sessionId);

    /** Single-use consume; 0 affected rows means the caller lost the race. */
    @Modifying
    @Query("UPDATE RefreshTokenEntity t SET t.consumedAt = :now "
            + "WHERE t.tokenHash = :hash AND t.consumedAt IS NULL")
    int consume(@Param("hash") String tokenHash, @Param("now") Instant now);

    /** Revokes every token of the session family (reuse detection, sign-out). */
    @Modifying
    @Query("UPDATE RefreshTokenEntity t SET t.revoked = true WHERE t.sessionId = :sessionId")
    int revokeFamily(@Param("sessionId") UUID sessionId);
}
