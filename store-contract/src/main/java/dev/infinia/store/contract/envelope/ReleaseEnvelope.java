package dev.infinia.store.contract.envelope;

import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;

import java.util.List;

/**
 * Unified release envelope shared by all five artifact classes (design §6.3).
 * Native package manifests remain authoritative; this envelope is the store-facing
 * view that is signed by the platform after review approval.
 */
public record ReleaseEnvelope(
        int schemaVersion,
        String coordinate,
        ListingType type,
        String version,
        Channel channel,
        String requiresHost,
        List<ArtifactRef> artifacts,
        List<DependencyRef> dependencies,
        List<PermissionRef> permissions,
        String publishedAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
