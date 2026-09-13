package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.InvitationCode;
import dev.infinia.store.domain.port.InvitationRepositories;
import dev.infinia.store.infrastructure.persistence.entity.InvitationCodeEntity;
import dev.infinia.store.infrastructure.persistence.repository.InvitationCodeJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class InvitationCodeAdapter
        implements InvitationRepositories.InvitationCodeRepository {

    private final InvitationCodeJpaRepository jpa;

    public InvitationCodeAdapter(InvitationCodeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public void save(InvitationCode code) {
        InvitationCodeEntity e = jpa.findById(code.id).orElseGet(InvitationCodeEntity::new);
        e.id = code.id;
        e.code = code.code;
        e.createdBy = code.createdBy;
        e.createdAt = code.createdAt;
        e.usedBy = code.usedBy;
        e.usedAt = code.usedAt;
        jpa.save(e);
    }

    @Override
    public Optional<InvitationCode> findByCode(String code) {
        return jpa.findByCode(code).map(InvitationCodeAdapter::toDomain);
    }

    @Override
    @Transactional
    public Optional<InvitationCode> findByCodeForUpdate(String code) {
        return jpa.findByCodeForUpdate(code).map(InvitationCodeAdapter::toDomain);
    }

    @Override
    public List<InvitationCode> findByCreatedBy(UUID createdBy) {
        return jpa.findByCreatedByOrderByCreatedAtDesc(createdBy).stream()
                .map(InvitationCodeAdapter::toDomain).toList();
    }

    @Override
    public long countByCreatedBySince(UUID createdBy, Instant since) {
        return jpa.countByCreatedByAndCreatedAtGreaterThanEqual(createdBy, since);
    }

    @Override
    public List<InvitationCode> findRecent(int limit) {
        return jpa.findTop200ByOrderByCreatedAtDesc().stream()
                .limit(limit)
                .map(InvitationCodeAdapter::toDomain).toList();
    }

    static InvitationCode toDomain(InvitationCodeEntity e) {
        InvitationCode code = new InvitationCode(e.id, e.code, e.createdBy, e.createdAt);
        code.usedBy = e.usedBy;
        code.usedAt = e.usedAt;
        return code;
    }
}
