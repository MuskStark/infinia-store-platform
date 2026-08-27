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
    private final IdentityRepositories.UserRepository users;
    private final PublishingRepositories.WebhookRepository webhooks;
    private final PublishingRepositories.AuditEventRepository auditEvents;
    private final CurrentPrincipal principal;
    private final AuditService audit;
    private static final SecureRandom RANDOM = new SecureRandom();

    OrganizationController(IdentityRepositories.OrganizationRepository organizations,
            IdentityRepositories.NamespaceRepository namespaces,
            IdentityRepositories.UserRepository users,
            PublishingRepositories.WebhookRepository webhooks,
            PublishingRepositories.AuditEventRepository auditEvents, CurrentPrincipal principal,
            AuditService audit) {
        this.organizations = organizations;
        this.namespaces = namespaces;
        this.users = users;
        this.webhooks = webhooks;
        this.auditEvents = auditEvents;
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

    // ---- member administration (design §7.3: ORG_ADMIN manages members and roles) ----

    @GetMapping("/{organizationId}/members")
    public List<ReviewDtos.OrganizationMemberDto> members(@PathVariable UUID organizationId) {
        var org = requireMembership(organizationId);
        return organizations.findMembers(organizationId).stream()
                .map(m -> {
                    var user = users.findById(m.userId()).orElse(null);
                    return new ReviewDtos.OrganizationMemberDto(m.userId().toString(),
                            user == null ? null : user.email,
                            user == null ? null : user.displayName,
                            m.role().name(), m.joinedAt().toString(),
                            m.userId().equals(org.ownerUserId()));
                })
                .toList();
    }

    @PostMapping("/{organizationId}/members")
    public ResponseEntity<ReviewDtos.OrganizationMemberDto> addMember(
            @PathVariable UUID organizationId,
            @RequestBody ReviewDtos.AddMemberRequest request) {
        UUID actor = principal.requireUserId();
        requireOrgAdmin(organizationId, actor);
        var user = users.findByEmailNormalized(
                dev.infinia.store.app.service.AccountService.normalizeEmail(request.email()))
                .orElseThrow(() -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "No store account uses this email"));
        var role = parseRole(request.role(), dev.infinia.store.contract.type.UserRole.PUBLISHER);
        if (organizations.findMemberRole(organizationId, user.id).isPresent()) {
            throw new DomainException(StoreErrorCode.IDEMPOTENCY_CONFLICT,
                    "User is already a member of this organization");
        }
        organizations.addMember(new dev.infinia.store.domain.model.Organization.Member(
                organizationId, user.id, role, Instant.now()));
        audit.record("USER", actor.toString(), "organization.member.add", "ORGANIZATION",
                organizationId.toString(), null, user.id.toString(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ReviewDtos.OrganizationMemberDto(
                user.id.toString(), user.email, user.displayName, role.name(),
                Instant.now().toString(), false));
    }

    @PutMapping("/{organizationId}/members/{memberId}/role")
    public ReviewDtos.OrganizationMemberDto changeRole(@PathVariable UUID organizationId,
            @PathVariable UUID memberId,
            @RequestBody ReviewDtos.ChangeMemberRoleRequest request) {
        UUID actor = principal.requireUserId();
        var org = requireOrgAdmin(organizationId, actor);
        if (memberId.equals(org.ownerUserId())) {
            throw new DomainException(StoreErrorCode.FORBIDDEN,
                    "The organization owner's role cannot be changed");
        }
        var role = parseRole(request.role(), null);
        if (organizations.findMemberRole(organizationId, memberId).isEmpty()) {
            throw new DomainException(StoreErrorCode.NOT_FOUND,
                    "User is not a member of this organization");
        }
        organizations.updateMemberRole(organizationId, memberId, role);
        audit.record("USER", actor.toString(), "organization.member.role", "ORGANIZATION",
                organizationId.toString(), null, memberId + ":" + role.name(), null);
        var user = users.findById(memberId).orElse(null);
        return new ReviewDtos.OrganizationMemberDto(memberId.toString(),
                user == null ? null : user.email, user == null ? null : user.displayName,
                role.name(), null, false);
    }

    @DeleteMapping("/{organizationId}/members/{memberId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID organizationId,
            @PathVariable UUID memberId) {
        UUID actor = principal.requireUserId();
        var org = requireOrgAdmin(organizationId, actor);
        if (memberId.equals(org.ownerUserId())) {
            throw new DomainException(StoreErrorCode.FORBIDDEN,
                    "The organization owner cannot be removed");
        }
        if (organizations.findMemberRole(organizationId, memberId).isEmpty()) {
            throw new DomainException(StoreErrorCode.NOT_FOUND,
                    "User is not a member of this organization");
        }
        organizations.removeMember(organizationId, memberId);
        audit.record("USER", actor.toString(), "organization.member.remove", "ORGANIZATION",
                organizationId.toString(), memberId.toString(), null, null);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{organizationId}/audit-events")
    public List<ReviewDtos.AuditEventDto> orgAuditEvents(@PathVariable UUID organizationId) {
        requireMembership(organizationId);
        // Org members read their own organization's trail from the append-only log
        // (design §14.3: events are keyed by the resource they touched).
        return auditEvents.findRecent(200, null).stream()
                .filter(e -> organizationId.toString().equals(e.resourceId()))
                .limit(100)
                .map(e -> new ReviewDtos.AuditEventDto(e.id().toString(), e.actorType(),
                        e.actorId(), e.action(), e.resourceType(), e.resourceId(),
                        e.beforeSummary(), e.afterSummary(), e.traceId(),
                        e.occurredAt().toString()))
                .toList();
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

    private dev.infinia.store.domain.model.Organization requireMembership(UUID organizationId) {
        UUID userId = principal.requireUserId();
        var org = organizations.findById(organizationId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "Organization not found"));
        if (!org.ownerUserId().equals(userId) && !organizations.isMember(organizationId, userId)) {
            throw DomainException.forbidden("Not a member of this organization");
        }
        return org;
    }

    private dev.infinia.store.domain.model.Organization requireOrgAdmin(UUID organizationId,
            UUID userId) {
        var org = organizations.findById(organizationId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "Organization not found"));
        if (!org.ownerUserId().equals(userId)
                && !organizations.hasRole(organizationId, userId,
                        dev.infinia.store.contract.type.UserRole.ORG_ADMIN)) {
            throw DomainException.forbidden("Organization admin role required");
        }
        return org;
    }

    private static dev.infinia.store.contract.type.UserRole parseRole(String role,
            dev.infinia.store.contract.type.UserRole fallback) {
        if (role == null || role.isBlank()) {
            if (fallback == null) {
                throw new DomainException(StoreErrorCode.VALIDATION_FAILED, "role is required");
            }
            return fallback;
        }
        try {
            return dev.infinia.store.contract.type.UserRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "role must be one of PUBLISHER, ORG_ADMIN, REVIEWER, PLATFORM_ADMIN, USER");
        }
    }

    private ReviewDtos.WebhookDto toDto(WebhookInfo webhook) {
        return new ReviewDtos.WebhookDto(webhook.id().toString(),
                webhook.organizationId().toString(), webhook.url(), webhook.events(),
                webhook.active(), webhook.createdAt().toString());
    }
}
