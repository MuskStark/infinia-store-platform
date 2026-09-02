package dev.infinia.store.contract.api;

import java.util.List;

/** DTOs for the publisher portal and the publishing pipeline (design §8). */
public final class PublisherDtos {

    private PublisherDtos() {}

    public record CreateListingRequest(
            String namespace,
            String slug,
            String type,
            String category,
            List<String> tags,
            String defaultChannel,
            String name,
            String summary,
            String descriptionMarkdown,
            String locale) {
    }

    public record CreateReleaseRequest(
            String version,
            String channel,
            String requiresHost,
            String license,
            String sourceUrl,
            String changelogMarkdown,
            List<ListingDtos.DependencyDto> dependencies,
            List<ListingDtos.PermissionDto> permissions,
            Integer rolloutPercent) {
    }

    public record UploadSessionDto(
            String uploadId,
            String releaseId,
            String filename,
            String kind,
            String platform,
            String arch,
            String variant,
            String uploadUrl,
            String method,
            String expiresAt,
            long maxSizeBytes) {
    }

    public record SubmitResultDto(
            String releaseId,
            String status,
            String reviewId,
            String submittedAt) {
    }

    public record PublisherReleaseDto(
            String releaseId,
            String listingCoordinate,
            String version,
            String status,
            String channel,
            String createdAt,
            String publishedAt,
            List<ListingDtos.ArtifactDto> artifacts,
            List<ScanFindingDto> findings) {
    }

    public record ScanFindingDto(
            String severity,
            String rule,
            String message) {
    }
}
