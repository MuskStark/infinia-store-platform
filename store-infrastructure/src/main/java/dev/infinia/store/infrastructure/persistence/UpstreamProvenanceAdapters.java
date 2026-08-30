package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.SyncRun;
import dev.infinia.store.domain.model.UpstreamItem;
import dev.infinia.store.domain.model.UpstreamRelease;
import dev.infinia.store.domain.port.UpstreamRepositories;
import dev.infinia.store.infrastructure.persistence.entity.SyncRunEntity;
import dev.infinia.store.infrastructure.persistence.entity.UpstreamItemEntity;
import dev.infinia.store.infrastructure.persistence.entity.UpstreamReleaseEntity;
import dev.infinia.store.infrastructure.persistence.repository.SyncRunJpaRepository;
import dev.infinia.store.infrastructure.persistence.repository.UpstreamItemJpaRepository;
import dev.infinia.store.infrastructure.persistence.repository.UpstreamReleaseJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JPA adapters for the upstream provenance ports (plan §9 infrastructure). */
@Component
class UpstreamProvenanceAdapters {

    @Component
    static class UpstreamItemAdapter implements UpstreamRepositories.UpstreamItemRepository {
        private final UpstreamItemJpaRepository jpa;

        UpstreamItemAdapter(UpstreamItemJpaRepository jpa) {
            this.jpa = jpa;
        }

        @Override
        public void save(UpstreamItem item) {
            UpstreamItemEntity e = jpa.findById(item.id()).orElseGet(UpstreamItemEntity::new);
            e.id = item.id();
            e.sourceId = item.sourceId();
            e.externalId = item.externalId();
            e.listingId = item.listingId();
            e.sourceUrl = item.sourceUrl();
            e.sourcePath = item.sourcePath();
            e.ref = item.ref();
            e.commitSha = item.commitSha();
            e.upstreamVersion = item.upstreamVersion();
            e.contentSha256 = item.contentSha256();
            e.firstSeenAt = item.firstSeenAt();
            e.lastSeenAt = item.lastSeenAt();
            e.removedAt = item.removedAt();
            jpa.save(e);
        }

        @Override
        public Optional<UpstreamItem> findById(UUID id) {
            return jpa.findById(id).map(UpstreamItemAdapter::toDomain);
        }

        @Override
        public Optional<UpstreamItem> findExact(UUID sourceId, String externalId,
                String contentSha256) {
            return jpa.findBySourceIdAndExternalIdAndContentSha256(sourceId, externalId,
                    contentSha256).map(UpstreamItemAdapter::toDomain);
        }

        @Override
        public Optional<UpstreamItem> findLatest(UUID sourceId, String externalId) {
            return jpa.findFirstBySourceIdAndExternalIdOrderByLastSeenAtDesc(sourceId,
                    externalId).map(UpstreamItemAdapter::toDomain);
        }

        @Override
        public Optional<UpstreamItem> findLatestByListingId(UUID listingId) {
            return jpa.findFirstByListingIdOrderByLastSeenAtDesc(listingId)
                    .map(UpstreamItemAdapter::toDomain);
        }

        @Override
        public List<UpstreamItem> findBySource(UUID sourceId) {
            return jpa.findBySourceId(sourceId).stream()
                    .map(UpstreamItemAdapter::toDomain).toList();
        }

        private static UpstreamItem toDomain(UpstreamItemEntity e) {
            return new UpstreamItem(e.id, e.sourceId, e.externalId, e.listingId,
                    e.sourceUrl, e.sourcePath, e.ref, e.commitSha, e.upstreamVersion,
                    e.contentSha256, e.firstSeenAt, e.lastSeenAt, e.removedAt);
        }
    }

    @Component
    static class UpstreamReleaseAdapter
            implements UpstreamRepositories.UpstreamReleaseRepository {
        private final UpstreamReleaseJpaRepository jpa;

        UpstreamReleaseAdapter(UpstreamReleaseJpaRepository jpa) {
            this.jpa = jpa;
        }

        @Override
        public void save(UpstreamRelease release) {
            UpstreamReleaseEntity e = jpa.findById(release.id())
                    .orElseGet(UpstreamReleaseEntity::new);
            e.id = release.id();
            e.upstreamItemId = release.upstreamItemId();
            e.listingReleaseId = release.listingReleaseId();
            e.sourceCommitSha = release.sourceCommitSha();
            e.sourceVersion = release.sourceVersion();
            e.normalizedSha256 = release.normalizedSha256();
            e.syncRunId = release.syncRunId();
            e.createdAt = release.createdAt();
            jpa.save(e);
        }

        @Override
        public List<UpstreamRelease> findByItemId(UUID upstreamItemId) {
            return jpa.findByUpstreamItemId(upstreamItemId).stream()
                    .map(e -> new UpstreamRelease(e.id, e.upstreamItemId,
                            e.listingReleaseId, e.sourceCommitSha, e.sourceVersion,
                            e.normalizedSha256, e.syncRunId, e.createdAt)).toList();
        }
    }

    @Component
    static class SyncRunAdapter implements UpstreamRepositories.SyncRunRepository {
        private final SyncRunJpaRepository jpa;

        SyncRunAdapter(SyncRunJpaRepository jpa) {
            this.jpa = jpa;
        }

        @Override
        public void save(SyncRun run) {
            SyncRunEntity e = jpa.findById(run.id()).orElseGet(SyncRunEntity::new);
            e.id = run.id();
            e.sourceId = run.sourceId();
            e.startedAt = run.startedAt();
            e.finishedAt = run.finishedAt();
            e.imported = run.imported();
            e.skipped = run.skipped();
            e.failed = run.failed();
            e.status = run.status();
            e.errors = run.errors();
            jpa.save(e);
        }
    }
}
