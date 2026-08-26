package dev.infinia.store.app.web;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.app.service.CatalogService;
import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.PublisherService;
import dev.infinia.store.app.service.ReviewService;
import dev.infinia.store.app.service.TicketService;
import dev.infinia.store.contract.api.PublisherDtos;
import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.Platform;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.UploadSessionInfo;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.PublishingRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Publisher portal API (design §8, §10.2). */
@RestController
@RequestMapping("/api/v1/publisher")
public class PublisherController {

    private final PublisherService publisher;
    private final CatalogService catalog;
    private final ListingRepository listings;
    private final PublishingRepositories.UploadSessionRepository uploads;
    private final PublishingRepositories.ReviewRepository reviews;
    private final ReviewService reviewService;
    private final TicketService tickets;
    private final StoreProperties properties;
    private final CurrentPrincipal principal;

    public PublisherController(PublisherService publisher, CatalogService catalog,
            ListingRepository listings,
            PublishingRepositories.UploadSessionRepository uploads,
            PublishingRepositories.ReviewRepository reviews, ReviewService reviewService,
            TicketService tickets, StoreProperties properties, CurrentPrincipal principal) {
        this.publisher = publisher;
        this.catalog = catalog;
        this.listings = listings;
        this.uploads = uploads;
        this.reviews = reviews;
        this.reviewService = reviewService;
        this.tickets = tickets;
        this.properties = properties;
        this.principal = principal;
    }

    @GetMapping("/listings")
    public List<dev.infinia.store.contract.api.CatalogDtos.CatalogItemDto> myListings() {
        return listings.findByPublisher(principal.requireUserId()).stream()
                .map(l -> new dev.infinia.store.contract.api.CatalogDtos.CatalogItemDto(
                        l.coordinate().toString(), l.type.name(), l.namespace, l.slug,
                        l.name("en"), l.summary("en"), l.category, l.tags, l.iconUrl, null,
                        l.defaultChannel.name().toLowerCase(), l.downloads, l.namespace,
                        l.updatedAt.toString()))
                .toList();
    }

    @PostMapping("/listings")
    public ResponseEntity<dev.infinia.store.contract.api.ListingDtos.ListingDetailDto> createListing(
            @RequestBody PublisherDtos.CreateListingRequest request) {
        Listing listing = publisher.createListing(principal.requireUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DtoMapper.listingDetail(listing, List.of(), 0));
    }

    @PostMapping("/listings/{listingId}/releases")
    public ResponseEntity<PublisherDtos.PublisherReleaseDto> createRelease(
            @PathVariable UUID listingId, @RequestBody PublisherDtos.CreateReleaseRequest request) {
        Listing listing = listings.findById(listingId).orElseThrow();
        Release release = publisher.createDraftRelease(principal.requireUserId(), listing,
                request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DtoMapper.publisherRelease(release, listing, List.of()));
    }

    @PostMapping("/releases/{releaseId}/uploads")
    public ResponseEntity<PublisherDtos.UploadSessionDto> createUpload(
            @PathVariable UUID releaseId, @RequestBody UploadRequest request) {
        Release release = catalog.releaseOrThrow(releaseId);
        UploadSessionInfo session = publisher.createUploadSession(principal.requireUserId(),
                release, request.filename(),
                request.kind() == null ? null
                        : ArtifactKind.valueOf(request.kind().trim().toUpperCase()),
                request.platform() == null ? null
                        : Platform.valueOf(request.platform().trim().toUpperCase()),
                request.arch() == null ? null
                        : Arch.valueOf(request.arch().trim().toUpperCase()),
                request.size() == null ? 0 : request.size());
        Instant expiresAt = session.expiresAt;
        String signature = tickets.sign("upload", session.id.toString(), expiresAt);
        // Server-relative presigned URL (design §8.2 step 1); clients resolve against
        // the API host they already use.
        String uploadUrl = "/api/v1/blobs/uploads/" + session.id + "?"
                + TicketService.encodeTicketParams("upload", session.id.toString(), expiresAt,
                        signature);
        return ResponseEntity.status(HttpStatus.CREATED).body(new PublisherDtos.UploadSessionDto(
                session.id.toString(), session.releaseId.toString(), session.filename,
                session.kind.name(), session.platform.name().toLowerCase(),
                session.arch.name().toLowerCase(), uploadUrl, "PUT", expiresAt.toString(),
                properties.maxUploadBytes()));
    }

    @PostMapping("/releases/{releaseId}/submit")
    public ResponseEntity<PublisherDtos.SubmitResultDto> submit(@PathVariable UUID releaseId) {
        Release release = catalog.releaseOrThrow(releaseId);
        var review = publisher.submit(principal.requireUserId(), release);
        return ResponseEntity.accepted().body(new PublisherDtos.SubmitResultDto(
                release.id.toString(), release.status.name(), review.id.toString(),
                review.submittedAt.toString()));
    }

    @GetMapping("/releases/{releaseId}")
    public PublisherDtos.PublisherReleaseDto release(@PathVariable UUID releaseId) {
        Release release = catalog.releaseOrThrow(releaseId);
        Listing listing = listings.findById(release.listingId).orElse(null);
        var findings = reviews.findLatestByReleaseId(release.id)
                .map(reviewService::findingsOf)
                .orElse(List.of());
        return DtoMapper.publisherRelease(release, listing, findings);
    }

    public record UploadRequest(String filename, String kind, String platform, String arch,
            Long size) {}
}
