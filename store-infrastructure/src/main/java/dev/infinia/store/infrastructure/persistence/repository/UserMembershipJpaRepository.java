package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.UserMembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserMembershipJpaRepository extends JpaRepository<UserMembershipEntity, UUID> {
}
