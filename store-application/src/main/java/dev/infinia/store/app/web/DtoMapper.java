package dev.infinia.store.app.web;

import dev.infinia.store.contract.api.ListingDtos;
import dev.infinia.store.contract.api.PublisherDtos;
import dev.infinia.store.contract.api.ReviewDtos;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Review;

/** Maps domain objects to contract DTOs. */
public final class DtoMapper {

    private DtoMapper() {}

    public static ListingDtos.ListingDetailDto listingDetail(Listing listing,
            java.util.List<Release> releases, long favoriteCount) {
        return new ListingDtos.ListingDetailDto(
                listing.id.toString(),
                listing.coordinate().toString(),
                listing.type.name(),
                listing.namespace,
                listing.slug,
                listing.visibility.name(),
                listing.status,
                listing.category,
                listing.tags,
                listing.iconUrl,
                listing.screenshots,
                listing.defaultChannel.name().toLowerCase(),
                listing.namespace,
                listing.downloads,
                favoriteCount,
                listing.createdAt.toString(),
                listing.updatedAt.toString(),
                listing.localizations.stream()
                        .map(l -> new ListingDtos.LocalizationDto(l.locale(), l.name(),
                                l.summary(), l.descriptionMarkdown(), l.changelogMarkdown()))
                        .toList(),
                releases.stream().map(DtoMapper::release).toList());
    }

    public static ListingDtos.ListingReleaseDto release(Release release) {
        return new ListingDtos.ListingReleaseDto(
                release.id.toString(),
                release.version.toString(),
                release.status.name(),
                release.channel.name().toLowerCase(),
                release.publishedAt == null ? null : release.publishedAt.toString(),
                release.createdAt.toString(),
                release.requiresHost,
                release.license,
                release.sourceUrl,
                release.changelogMarkdown,
                release.rolloutPercent,
                release.artifacts.stream().map(DtoMapper::artifact).toList(),
                release.dependencies.stream()
                        .map(d -> new ListingDtos.DependencyDto(d.coordinate(), d.range(),
                                d.optional()))
                        .toList(),
                release.permissions.stream()
                        .map(p -> new ListingDtos.PermissionDto(p.permissionId(), p.scope(),
                                p.required(), p.reason()))
                        .toList());
    }

    public static ListingDtos.ArtifactDto artifact(Release.ArtifactInfo a) {
        return new ListingDtos.ArtifactDto(
                a.id() == null ? null : a.id().toString(),
                a.kind().name(),
                a.platform().name().toLowerCase(),
                a.arch().name().toLowerCase(),
                a.filename(),
                a.size(),
                a.sha256(),
                a.keyId(),
                a.mimeType());
    }

    public static ReviewDtos.ReviewDto review(Review review, Listing listing, Release release,
            java.util.List<PublisherDtos.ScanFindingDto> findings) {
        return new ReviewDtos.ReviewDto(
                review.id.toString(),
                review.releaseId.toString(),
                listing == null ? null : listing.coordinate().toString(),
                listing == null ? null : listing.name("en"),
                release == null ? null : release.version.toString(),
                review.status,
                review.submittedAt == null ? null : review.submittedAt.toString(),
                review.decidedAt == null ? null : review.decidedAt.toString(),
                review.reviewerId == null ? null : review.reviewerId.toString(),
                review.notes,
                findings);
    }

    public static PublisherDtos.PublisherReleaseDto publisherRelease(Release release,
            Listing listing, java.util.List<PublisherDtos.ScanFindingDto> findings) {
        return new PublisherDtos.PublisherReleaseDto(
                release.id.toString(),
                listing == null ? null : listing.coordinate().toString(),
                release.version.toString(),
                release.status.name(),
                release.channel.name().toLowerCase(),
                release.createdAt.toString(),
                release.publishedAt == null ? null : release.publishedAt.toString(),
                findings);
    }
}
