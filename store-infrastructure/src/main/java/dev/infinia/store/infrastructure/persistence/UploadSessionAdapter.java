package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.Platform;
import dev.infinia.store.domain.model.UploadSessionInfo;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.infrastructure.persistence.entity.UploadSessionEntity;
import dev.infinia.store.infrastructure.persistence.repository.UploadSessionJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class UploadSessionAdapter implements PublishingRepositories.UploadSessionRepository {

    private final UploadSessionJpaRepository jpa;

    public UploadSessionAdapter(UploadSessionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<UploadSessionInfo> findById(UUID id) {
        return jpa.findById(id).map(UploadSessionAdapter::toDomain);
    }

    @Override
    public void save(UploadSessionInfo session) {
        UploadSessionEntity e = jpa.findById(session.id).orElseGet(UploadSessionEntity::new);
        e.id = session.id;
        e.releaseId = session.releaseId;
        e.filename = session.filename;
        e.kind = session.kind.name();
        e.platform = session.platform.name();
        e.arch = session.arch.name();
        e.declaredSize = session.declaredSize;
        e.status = session.status;
        e.expiresAt = session.expiresAt;
        e.blobKey = session.blobKey;
        e.sha256 = session.sha256;
        e.mimeType = session.mimeType;
        jpa.save(e);
    }

    @Override
    public List<UploadSessionInfo> findByReleaseId(UUID releaseId) {
        return jpa.findByReleaseId(releaseId).stream().map(UploadSessionAdapter::toDomain).toList();
    }

    static UploadSessionInfo toDomain(UploadSessionEntity e) {
        UploadSessionInfo s = new UploadSessionInfo();
        s.id = e.id;
        s.releaseId = e.releaseId;
        s.filename = e.filename;
        s.kind = ArtifactKind.valueOf(e.kind);
        s.platform = Platform.valueOf(e.platform);
        s.arch = Arch.valueOf(e.arch);
        s.declaredSize = e.declaredSize;
        s.status = e.status;
        s.expiresAt = e.expiresAt;
        s.blobKey = e.blobKey;
        s.sha256 = e.sha256;
        s.mimeType = e.mimeType;
        return s;
    }
}
