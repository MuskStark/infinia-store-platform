package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.contract.semver.SemVer;
import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.Platform;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.port.ReleaseRepository;
import dev.infinia.store.infrastructure.persistence.entity.ReleaseEntity;
import dev.infinia.store.infrastructure.persistence.repository.ListingJpaRepository;
import dev.infinia.store.infrastructure.persistence.repository.ReleaseJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class ReleaseRepositoryAdapter implements ReleaseRepository {

    private static final List<String> VISIBLE_STATUSES =
            List.of(ReleaseStatus.PUBLISHED.name(), ReleaseStatus.DEPRECATED.name());

    private final ReleaseJpaRepository jpa;
    private final ListingJpaRepository listingJpa;

    public ReleaseRepositoryAdapter(ReleaseJpaRepository jpa, ListingJpaRepository listingJpa) {
        this.jpa = jpa;
        this.listingJpa = listingJpa;
    }

    @Override
    public Optional<Release> findById(UUID id) {
        return jpa.findById(id).map(ReleaseRepositoryAdapter::toDomain);
    }

    @Override
    public List<Release> findByListingId(UUID listingId) {
        return sortedByVersionDesc(jpa.findByListingId(listingId));
    }

    @Override
    public Optional<Release> findByListingIdAndVersion(UUID listingId, String version) {
        return jpa.findByListingIdAndVersion(listingId, version)
                .map(ReleaseRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existsByListingIdAndStatus(UUID listingId, String version,
            Iterable<ReleaseStatus> statuses) {
        return jpa.findByListingId(listingId).stream()
                .anyMatch(e -> e.version.equals(version) && contains(statuses, e.status));
    }

    private static boolean contains(Iterable<ReleaseStatus> statuses, String value) {
        for (ReleaseStatus s : statuses) {
            if (s.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<Release> findLatestVisible(UUID listingId, Channel channel) {
        return jpa.findByListingId(listingId).stream()
                .filter(e -> VISIBLE_STATUSES.contains(e.status))
                .filter(e -> channel == null || Channel.valueOf(e.channel) == channel)
                .filter(e -> parseSafe(e.version) != null)
                .max(Comparator.comparing(e -> parseSafe(e.version)))
                .map(ReleaseRepositoryAdapter::toDomain);
    }

    @Override
    public List<Release> findVisibleByListingId(UUID listingId) {
        return jpa.findByListingId(listingId).stream()
                .filter(e -> VISIBLE_STATUSES.contains(e.status))
                .map(ReleaseRepositoryAdapter::toDomain)
                .sorted(Comparator.comparing((Release r) -> r.version).reversed())
                .toList();
    }

    @Override
    public List<Release> findVisibleByType(dev.infinia.store.contract.type.ListingType type) {
        // Type lives on the listing, not the release — resolve through the parent rows.
        java.util.Set<UUID> listingIdsOfType = listingJpa.findAll().stream()
                .filter(l -> type.name().equals(l.type))
                .map(l -> l.id)
                .collect(java.util.stream.Collectors.toSet());
        if (listingIdsOfType.isEmpty()) {
            return List.of();
        }
        return jpa.findByStatusIn(List.of(ReleaseStatus.PUBLISHED.name())).stream()
                .filter(e -> VISIBLE_STATUSES.contains(e.status))
                .filter(e -> listingIdsOfType.contains(e.listingId))
                .map(ReleaseRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void save(Release release) {
        ReleaseEntity entity = jpa.findById(release.id).orElseGet(ReleaseEntity::new);
        copy(release, entity);
        jpa.save(entity);
    }

    private static List<Release> sortedByVersionDesc(List<ReleaseEntity> entities) {
        List<Release> result = new ArrayList<>();
        for (ReleaseEntity e : entities) {
            SemVer v = parseSafe(e.version);
            if (v != null) {
                result.add(toDomain(e));
            }
        }
        result.sort(Comparator.comparing((Release r) -> r.version).reversed());
        return result;
    }

    private static SemVer parseSafe(String version) {
        try {
            return SemVer.parse(version);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void copy(Release r, ReleaseEntity e) {
        e.id = r.id;
        e.listingId = r.listingId;
        e.version = r.version.toString();
        e.status = r.status.name();
        e.channel = r.channel.name();
        e.publishedAt = r.publishedAt;
        e.createdAt = r.createdAt;
        e.requiresHost = r.requiresHost;
        e.license = r.license;
        e.sourceUrl = r.sourceUrl;
        e.changelogMarkdown = r.changelogMarkdown;
        e.rolloutPercent = r.rolloutPercent;
        e.artifacts.clear();
        if (r.artifacts != null) {
            for (Release.ArtifactInfo a : r.artifacts) {
                e.artifacts.add(new ReleaseEntity.ArtifactEmb(a.kind().name(),
                        a.platform().name(), a.arch().name(), a.filename(), a.size(), a.sha256(),
                        a.signature(), a.keyId(), a.blobKey(), a.mimeType()));
            }
        }
        e.dependencies.clear();
        if (r.dependencies != null) {
            for (Release.DependencyDecl d : r.dependencies) {
                e.dependencies.add(new ReleaseEntity.DependencyEmb(d.coordinate(), d.range(),
                        d.optional()));
            }
        }
        e.permissions.clear();
        if (r.permissions != null) {
            for (Release.PermissionDecl p : r.permissions) {
                e.permissions.add(new ReleaseEntity.PermissionEmb(p.permissionId(), p.scope(),
                        p.required(), p.reason()));
            }
        }
    }

    public static Release toDomain(ReleaseEntity e) {
        Release r = new Release();
        r.id = e.id;
        r.listingId = e.listingId;
        r.version = SemVer.parse(e.version);
        r.status = ReleaseStatus.valueOf(e.status);
        r.channel = Channel.valueOf(e.channel);
        r.publishedAt = e.publishedAt;
        r.createdAt = e.createdAt;
        r.requiresHost = e.requiresHost;
        r.license = e.license;
        r.sourceUrl = e.sourceUrl;
        r.changelogMarkdown = e.changelogMarkdown;
        r.rolloutPercent = e.rolloutPercent;
        r.artifacts = new ArrayList<>();
        for (ReleaseEntity.ArtifactEmb a : e.artifacts) {
            r.artifacts.add(new Release.ArtifactInfo(null,
                    ArtifactKind.valueOf(a.kind()), Platform.valueOf(a.platform()),
                    Arch.valueOf(a.arch()), a.filename(), a.size(), a.sha256(), a.signature(),
                    a.keyId(), a.blobKey(), a.mimeType()));
        }
        r.dependencies = new ArrayList<>();
        for (ReleaseEntity.DependencyEmb d : e.dependencies) {
            r.dependencies.add(new Release.DependencyDecl(d.coordinate(), d.range(), d.optional()));
        }
        r.permissions = new ArrayList<>();
        for (ReleaseEntity.PermissionEmb p : e.permissions) {
            r.permissions.add(new Release.PermissionDecl(p.permissionId(), p.scope(), p.required(),
                    p.reason()));
        }
        return r;
    }
}
