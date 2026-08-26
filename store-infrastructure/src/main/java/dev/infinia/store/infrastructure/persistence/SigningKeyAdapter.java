package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.SigningKeyInfo;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.infrastructure.persistence.entity.SigningKeyEntity;
import dev.infinia.store.infrastructure.persistence.repository.SigningKeyJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class SigningKeyAdapter implements PublishingRepositories.SigningKeyRepository {

    private final SigningKeyJpaRepository jpa;

    public SigningKeyAdapter(SigningKeyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<SigningKeyInfo> findByKeyId(String keyId) {
        return jpa.findById(keyId).map(SigningKeyAdapter::toDomain);
    }

    @Override
    public void save(SigningKeyInfo key) {
        SigningKeyEntity e = jpa.findById(key.keyId()).orElseGet(SigningKeyEntity::new);
        e.keyId = key.keyId();
        e.algorithm = key.algorithm();
        e.publicKeyBase64 = key.publicKeyBase64();
        e.ownerType = key.ownerType();
        e.ownerRef = key.ownerRef();
        e.status = key.status();
        e.validFrom = key.validFrom();
        e.validTo = key.validTo();
        jpa.save(e);
    }

    @Override
    public List<SigningKeyInfo> findByOwnerTypeAndStatus(String ownerType, String status) {
        return jpa.findByOwnerTypeAndStatus(ownerType, status).stream()
                .map(SigningKeyAdapter::toDomain)
                .toList();
    }

    private static SigningKeyInfo toDomain(SigningKeyEntity e) {
        return new SigningKeyInfo(e.keyId, e.algorithm, e.publicKeyBase64, e.ownerType, e.ownerRef,
                e.status, e.validFrom, e.validTo);
    }
}
