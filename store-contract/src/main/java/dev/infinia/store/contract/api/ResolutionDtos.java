package dev.infinia.store.contract.api;

import java.util.List;

/** DTOs for environment-aware version resolution (design §10.2 / §9.4). */
public final class ResolutionDtos {

    private ResolutionDtos() {}

    public record ClientEnvironment(
            String hostVersion,
            String os,
            String arch,
            String channel,
            List<InstalledRef> installed) {
    }

    public record InstalledRef(String coordinate, String version) {}

    public record ResolveRequest(
            String coordinate,
            String range,
            ClientEnvironment client) {
    }

    /** A resolved node in the dependency closure. */
    public record ResolutionItemDto(
            String coordinate,
            String releaseId,
            String version,
            String channel,
            List<ListingDtos.ArtifactDto> artifacts,
            List<ListingDtos.PermissionDto> permissions,
            String requiresHost,
            boolean alreadyInstalled,
            String reason) {
    }

    /** A dependency that could not be satisfied in this environment. */
    public record MissingDependencyDto(
            String coordinate,
            String range,
            String reason) {
    }

    public record ResolveResponseDto(
            boolean resolvable,
            String rootCoordinate,
            List<ResolutionItemDto> plan,
            List<MissingDependencyDto> missing,
            String resolvedAt) {
    }
}
