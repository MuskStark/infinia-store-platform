package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.UserMembership;
import dev.infinia.store.domain.port.BillingRepositories;
import dev.infinia.store.infrastructure.persistence.entity.UserMembershipEntity;
import dev.infinia.store.infrastructure.persistence.repository.UserMembershipJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
public class UserMembershipAdapter implements BillingRepositories.UserMembershipRepository {

    private final UserMembershipJpaRepository jpa;

    public UserMembershipAdapter(UserMembershipJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<UserMembership> findByUserId(UUID userId) {
        return jpa.findById(userId).map(UserMembershipAdapter::toDomain);
    }

    @Override
    @Transactional
    public void save(UserMembership membership) {
        UserMembershipEntity e = jpa.findById(membership.userId)
                .orElseGet(UserMembershipEntity::new);
        e.userId = membership.userId;
        e.level = membership.level;
        e.expiresAt = membership.expiresAt;
        e.updatedAt = membership.updatedAt;
        jpa.save(e);
    }

    static UserMembership toDomain(UserMembershipEntity e) {
        return new UserMembership(e.userId, e.level, e.expiresAt, e.updatedAt);
    }
}
