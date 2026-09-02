package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.StoreUser;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.infrastructure.persistence.entity.UserEntity;
import dev.infinia.store.infrastructure.persistence.repository.UserJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class UserAdapter implements IdentityRepositories.UserRepository {

    private final UserJpaRepository jpa;

    public UserAdapter(UserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<StoreUser> findById(UUID id) {
        return jpa.findById(id).map(UserAdapter::toDomain);
    }

    @Override
    public Optional<StoreUser> findByEmailNormalized(String emailNormalized) {
        return jpa.findByEmailNormalized(emailNormalized).map(UserAdapter::toDomain);
    }

    @Override
    public boolean existsByEmailNormalized(String emailNormalized) {
        return jpa.existsByEmailNormalized(emailNormalized);
    }

    @Override
    public List<StoreUser> findAll() {
        return jpa.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.ASC, "createdAt"))
                .stream().map(UserAdapter::toDomain).toList();
    }

    @Override
    @Transactional
    public void save(StoreUser user) {
        UserEntity e = jpa.findById(user.id).orElseGet(UserEntity::new);
        e.id = user.id;
        e.email = user.email;
        e.emailNormalized = user.emailNormalized;
        e.displayName = user.displayName;
        e.roles = String.join(",", user.roles.stream().map(Enum::name).toList());
        e.status = user.status;
        e.beeLevel = user.beeLevel;
        e.mfaEnabled = user.mfaEnabled;
        e.createdAt = user.createdAt;
        e.lastLoginAt = user.lastLoginAt;
        jpa.save(e);
    }

    static StoreUser toDomain(UserEntity e) {
        Set<dev.infinia.store.contract.type.UserRole> roles = new LinkedHashSet<>();
        if (e.roles != null) {
            for (String part : e.roles.split(",")) {
                if (!part.isBlank()) {
                    roles.add(dev.infinia.store.contract.type.UserRole.valueOf(part.trim()));
                }
            }
        }
        StoreUser user = new StoreUser(e.id, e.email, e.emailNormalized, e.displayName, roles,
                e.status, e.beeLevel, e.createdAt);
        user.mfaEnabled = e.mfaEnabled;
        user.lastLoginAt = e.lastLoginAt;
        return user;
    }
}
