package dev.infinia.store.app.web;

import dev.infinia.store.app.service.AuditService;
import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.ReviewService;
import dev.infinia.store.contract.api.PublisherDtos;
import dev.infinia.store.contract.api.ReviewDtos;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Review;
import dev.infinia.store.domain.model.WebhookInfo;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.port.ReleaseRepository;
import dev.infinia.store.domain.service.UuidV7;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Review queue and decisions (design §8.2, §10.2). */
@RestController
@RequestMapping("/api/v1")
class ReviewController {

    private final ReviewService reviews;
    private final ReleaseRepository releases;
    private final ListingRepository listings;
    private final CurrentPrincipal principal;

    public ReviewController(ReviewService reviews, ReleaseRepository releases,
            ListingRepository listings, CurrentPrincipal principal) {
        this.reviews = reviews;
        this.releases = releases;
        this.listings = listings;
        this.principal = principal;
    }

    @GetMapping("/reviews")
    public List<ReviewDtos.ReviewDto> queue(@RequestParam(required = false) String status) {
        return reviews.queue(status).stream().map(this::toDto).toList();
    }

    @PostMapping("/reviews/{reviewId}/decisions")
    public ReviewDtos.ReviewDto decide(@PathVariable UUID reviewId,
            @RequestBody ReviewDtos.ReviewDecisionRequest request) {
        UUID reviewerId = principal.current() == null ? null : principal.current().userId();
        Review review = reviews.decide(reviewerId, reviewId, request);
        return toDto(review);
    }

    @PostMapping("/admin/releases/{releaseId}/yank")
    public ResponseEntity<Void> yank(@PathVariable UUID releaseId,
            @RequestBody(required = false) ReasonBody body) {
        reviews.yank(principal.requireUserId(), releaseId, body == null ? null : body.reason());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/admin/releases/{releaseId}/quarantine")
    public ResponseEntity<Void> quarantine(@PathVariable UUID releaseId,
            @RequestBody(required = false) ReasonBody body) {
        reviews.quarantine(principal.requireUserId(), releaseId,
                body == null ? null : body.reason());
        return ResponseEntity.ok().build();
    }

    public record ReasonBody(String reason) {}

    private ReviewDtos.ReviewDto toDto(Review review) {
        Release release = releases.findById(review.releaseId).orElse(null);
        Listing listing = release == null ? null
                : listings.findById(release.listingId).orElse(null);
        return DtoMapper.review(review, listing, release, reviews.findingsOf(review));
    }
}

/** Organizations, namespaces and webhooks (design §7.1, §10.2). */
@RestController
@RequestMapping("/api/v1/organizations")
class OrganizationController {

    private final IdentityRepositories.OrganizationRepository organizations;
    private final IdentityRepositories.NamespaceRepository namespaces;
    private final PublishingRepositories.WebhookRepository webhooks;
    private final CurrentPrincipal principal;
    private final AuditService audit;
    private static final SecureRandom RANDOM = new SecureRandom();

    OrganizationController(IdentityRepositories.OrganizationRepository organizations,
            IdentityRepositories.NamespaceRepository namespaces,
            PublishingRepositories.WebhookRepository webhooks, CurrentPrincipal principal,
            AuditService audit) {
        this.organizations = organizations;
        this.namespaces = namespaces;
        this.webhooks = webhooks;
        this.principal = principal;
        this.audit = audit;
    }

    @PostMapping
    public ResponseEntity<ReviewDtos.OrganizationDto> create(
            @RequestBody ReviewDtos.CreateOrganizationRequest request) {
        UUID userId = principal.requireUserId();
        if (request.slug() == null || request.slug().isBlank() || !request.slug()
                .matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "slug must match [a-z0-9][a-z0-9-]{0,62}");
        }
        if (organizations.existsBySlug(request.slug())) {
            throw new DomainException(StoreErrorCode.NAMESPACE_TAKEN,
                    "Organization slug already taken");
        }
        Instant now = Instant.now();
        UUID orgId = UuidV7.generate();
        organizations.save(new dev.infinia.store.domain.model.Organization(orgId, request.slug(),
                request.name() == null ? request.slug() : request.name(), userId, now));
        organizations.addMember(
                new dev.infinia.store.domain.model.Organization.Member(orgId, userId,
                        dev.infinia.store.contract.type.UserRole.ORG_ADMIN, now));
        // Creating an organization reserves the matching namespace (design §7.1).
        namespaces.save(new dev.infinia.store.domain.model.Namespace(UuidV7.generate(),
                request.slug(), null, orgId, false, now));
        audit.record("USER", userId.toString(), "organization.create", "ORGANIZATION",
                orgId.toString(), null, request.slug(), null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ReviewDtos.OrganizationDto(orgId.toString(), request.slug(),
                        request.name() == null ? request.slug() : request.name(),
                        now.toString()));
    }

    @GetMapping
    public List<ReviewDtos.OrganizationDto> list() {
        return organizations.findByMember(principal.requireUserId()).stream()
                .map(o -> new ReviewDtos.OrganizationDto(o.id().toString(), o.slug(), o.name(),
                        o.createdAt().toString()))
                .toList();
    }

    @GetMapping("/{organizationId}/webhooks")
    public List<ReviewDtos.WebhookDto> listWebhooks(@PathVariable UUID organizationId) {
        requireMembership(organizationId);
        return webhooks.findByOrganizationId(organizationId).stream().map(this::toDto).toList();
    }

    @PostMapping("/{organizationId}/webhooks")
    public ResponseEntity<ReviewDtos.WebhookDto> createWebhook(
            @PathVariable UUID organizationId,
            @RequestBody ReviewDtos.CreateWebhookRequest request) {
        requireMembership(organizationId);
        if (request.url() == null || !request.url().startsWith("https://")) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "Webhook URLs must use HTTPS");
        }
        byte[] secretBytes = new byte[24];
        RANDOM.nextBytes(secretBytes);
        WebhookInfo webhook = new WebhookInfo(UuidV7.generate(), organizationId, request.url(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes),
                request.events() == null ? List.of() : request.events(), true, Instant.now());
        webhooks.save(webhook);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(webhook));
    }

    private void requireMembership(UUID organizationId) {
        UUID userId = principal.requireUserId();
        var org = organizations.findById(organizationId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "Organization not found"));
        if (!org.ownerUserId().equals(userId) && !organizations.isMember(organizationId, userId)) {
            throw DomainException.forbidden("Not a member of this organization");
        }
    }

    private ReviewDtos.WebhookDto toDto(WebhookInfo webhook) {
        return new ReviewDtos.WebhookDto(webhook.id().toString(),
                webhook.organizationId().toString(), webhook.url(), webhook.events(),
                webhook.active(), webhook.createdAt().toString());
    }
}
