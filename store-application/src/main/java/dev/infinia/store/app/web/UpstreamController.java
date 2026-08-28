package dev.infinia.store.app.web;

import dev.infinia.store.app.service.AuditService;
import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.UpstreamSyncService;
import dev.infinia.store.contract.api.ReviewDtos;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.UpstreamSource;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.service.UuidV7;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Upstream aggregation administration (design §2.1) — PLATFORM_ADMIN only.
 * Hosts stop configuring external marketplaces themselves; the store mirrors
 * them and stays the single source.
 */
@RestController
@RequestMapping("/api/v1/admin/upstreams")
class UpstreamController {

    private final PublishingRepositories.UpstreamSourceRepository upstreams;
    private final UpstreamSyncService sync;
    private final CurrentPrincipal principal;
    private final AuditService audit;

    UpstreamController(PublishingRepositories.UpstreamSourceRepository upstreams,
            UpstreamSyncService sync, CurrentPrincipal principal, AuditService audit) {
        this.upstreams = upstreams;
        this.sync = sync;
        this.principal = principal;
        this.audit = audit;
    }

    @GetMapping
    public List<ReviewDtos.UpstreamDto> list() {
        principal.requireUserId();
        return upstreams.findAll().stream().map(UpstreamController::toDto).toList();
    }

    @PostMapping
    public ResponseEntity<ReviewDtos.UpstreamDto> create(
            @RequestBody CreateUpstreamRequest request) {
        UUID adminId = principal.requireUserId();
        if (request.name() == null || request.name().isBlank()
                || request.marketplaceUrl() == null || !request.marketplaceUrl()
                        .matches("^https?://.+")
                || request.targetNamespace() == null || !request.targetNamespace()
                        .matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "name, https marketplaceUrl and targetNamespace are required");
        }
        if (upstreams.findByName(request.name()).isPresent()) {
            throw new DomainException(StoreErrorCode.IDEMPOTENCY_CONFLICT,
                    "Upstream name already exists");
        }
        if (request.adapterType() != null && !request.adapterType().isBlank()
                && !List.of("AUTO", "CLAUDE_MARKETPLACE", "SKILL_REPOSITORY", "MCP_REGISTRY")
                        .contains(request.adapterType().trim().toUpperCase())) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "adapterType must be AUTO, CLAUDE_MARKETPLACE, SKILL_REPOSITORY"
                            + " or MCP_REGISTRY");
        }
        UpstreamSource source = new UpstreamSource(UuidV7.generate(), request.name(),
                request.marketplaceUrl(), request.targetNamespace(), true, null, null, null,
                request.adapterType() == null ? null
                        : request.adapterType().trim().toUpperCase());
        upstreams.save(source);
        audit.record("USER", adminId.toString(), "upstream.create", "UPSTREAM",
                source.id().toString(), null, source.marketplaceUrl(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(source));
    }

    /** Runs the aggregation now and returns the per-run summary. */
    @PostMapping("/{upstreamId}/sync")
    public UpstreamSyncService.SyncResult trigger(@PathVariable UUID upstreamId) {
        UUID adminId = principal.requireUserId();
        UpstreamSyncService.SyncResult result = sync.sync(upstreamId);
        audit.record("USER", adminId.toString(), "upstream.sync.request", "UPSTREAM",
                upstreamId.toString(), null,
                "imported=" + result.imported() + ",failed=" + result.failed(), null);
        return result;
    }

    record CreateUpstreamRequest(String name, String marketplaceUrl, String targetNamespace,
                String adapterType) {}

    private static ReviewDtos.UpstreamDto toDto(UpstreamSource source) {
        return new ReviewDtos.UpstreamDto(source.id().toString(), source.name(),
                source.marketplaceUrl(), source.targetNamespace(), source.adapterType(),
                source.enabled(),
                source.lastSyncAt() == null ? null : source.lastSyncAt().toString(),
                source.lastSyncOk(), source.lastError());
    }
}
