package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.Credential;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.infrastructure.persistence.entity.CredentialEntity;
import dev.infinia.store.infrastructure.persistence.repository.CredentialJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class CredentialAdapter implements IdentityRepositories.CredentialRepository {

    private final CredentialJpaRepository jpa;

    public CredentialAdapter(CredentialJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(Credential credential) {
        CredentialEntity e = new CredentialEntity();
        e.id = credential.id();
        e.userId = credential.userId();
        e.type = credential.type().name();
        e.secretHash = credential.secretHash();
        e.createdAt = credential.createdAt();
        jpa.save(e);
    }

    @Override
    public Optional<Credential> findByUserIdAndType(UUID userId, Credential.CredentialType type) {
        return jpa.findByUserIdAndType(userId, type.name())
                .map(c -> new Credential(c.id, c.userId, Credential.CredentialType.valueOf(c.type),
                        c.secretHash, c.createdAt));
    }
}
