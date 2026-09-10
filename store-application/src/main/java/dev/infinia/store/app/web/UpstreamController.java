package dev.infinia.store.app.web;

import dev.infinia.store.app.service.AuditService;
import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.UpstreamSyncService;
import dev.infinia.store.contract.api.ReviewDtos;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.SyncRun;
import dev.infinia.store.domain.model.UpstreamSource;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.port.UpstreamRepositories;
import dev.infinia.store.domain.service.UuidV7;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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
    private final UpstreamRepositories.SyncRunRepository syncRuns;
    private final UpstreamSyncService sync;
    private final CurrentPrincipal principal;
    private final AuditService audit;

    UpstreamController(PublishingRepositories.UpstreamSourceRepository upstreams,
            UpstreamRepositories.SyncRunRepository syncRuns, UpstreamSyncService sync,
            CurrentPrincipal principal, AuditService audit) {
        this.upstreams = upstreams;
        this.syncRuns = syncRuns;
        this.sync = sync;
        this.principal = principal;
        this.audit = audit;
    }

    @GetMapping
    public List<ReviewDtos.UpstreamDto> list() {
        principal.requireUserId();
        List<ReviewDtos.UpstreamDto> rows = new ArrayList<>();
        for (UpstreamSource source : upstreams.findAll()) {
            rows.add(toDto(source, syncRuns.findLatestBySourceId(source.id()).orElse(null)));
        }
        return rows;
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
                && !List.of("AUTO", "CLAUDE_MARKETPLACE", "SKILL_REPOSITORY", "MCP_REGISTRY",
                        "SKILLHUB_REGISTRY").contains(request.adapterType().trim()
                                .toUpperCase())) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "adapterType must be AUTO, CLAUDE_MARKETPLACE, SKILL_REPOSITORY,"
                            + " MCP_REGISTRY or SKILLHUB_REGISTRY");
        }
        UpstreamSource source = new UpstreamSource(UuidV7.generate(), request.name(),
                request.marketplaceUrl(), request.targetNamespace(), true, null, null, null,
                request.adapterType() == null ? null
                        : request.adapterType().trim().toUpperCase());
        upstreams.save(source);
        audit.record("USER", adminId.toString(), "upstream.create", "UPSTREAM",
                source.id().toString(), null, source.marketplaceUrl(), null);
        // Registration indexes the source right away so the public catalog does
        // not stay empty until an administrator notices the separate sync action;
        // the aggregation itself runs in the background (a full run takes
        // minutes), so the response returns while the run row already reads
        // SYNCING and the console polls it to OK/FAILED.
        SyncRun run = sync.startBackgroundSync(source.id());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toDto(upstreams.findById(source.id()).orElse(source), run));
    }

    /**
     * Kicks off an aggregation run in the background. The per-run summary is
     * served by the sync log ({@code GET /{upstreamId}/sync-runs}) — a full run
     * takes minutes, so the request itself must not block on it.
     */
    @PostMapping("/{upstreamId}/sync")
    public ResponseEntity<ReviewDtos.UpstreamDto> trigger(@PathVariable UUID upstreamId) {
        UUID adminId = principal.requireUserId();
        UpstreamSource source = upstreams.findById(upstreamId).orElseThrow(
                () -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "Upstream source not found"));
        SyncRun run = sync.startBackgroundSync(upstreamId);
        audit.record("USER", adminId.toString(), "upstream.sync.request", "UPSTREAM",
                upstreamId.toString(), null, "run=" + run.id(), null);
        return ResponseEntity.accepted()
                .body(toDto(upstreams.findById(source.id()).orElse(source), run));
    }

    /** The sync log: recent runs of one source, newest first. */
    @GetMapping("/{upstreamId}/sync-runs")
    public List<ReviewDtos.UpstreamSyncRunDto> runs(@PathVariable UUID upstreamId) {
        principal.requireUserId();
        upstreams.findById(upstreamId).orElseThrow(
                () -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "Upstream source not found"));
        return syncRuns.findRecentBySourceId(upstreamId, 20).stream()
                .map(UpstreamController::toRunDto).toList();
    }

    /**
     * Enables/disables a source at runtime. A persistently failing optional
     * mirror must not paint the status page yellow until the next reboot:
     * operators park it here, and the upstream probe skips disabled sources.
     */
    @PatchMapping("/{upstreamId}")
    public ReviewDtos.UpstreamDto setEnabled(@PathVariable UUID upstreamId,
            @RequestBody UpdateUpstreamRequest request) {
        UUID adminId = principal.requireUserId();
        if (request.enabled() == null) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "enabled is required (true or false)");
        }
        UpstreamSource source = upstreams.findById(upstreamId).orElseThrow(
                () -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "Upstream source not found"));
        UpstreamSource updated = new UpstreamSource(source.id(), source.name(),
                source.marketplaceUrl(), source.targetNamespace(), request.enabled(),
                source.lastSyncAt(), source.lastSyncOk(), source.lastError(),
                source.adapterType());
        upstreams.save(updated);
        audit.record("USER", adminId.toString(),
                request.enabled() ? "upstream.enable" : "upstream.disable", "UPSTREAM",
                upstreamId.toString(), null, source.marketplaceUrl(), null);
        return toDto(updated, syncRuns.findLatestBySourceId(updated.id()).orElse(null));
    }

    record CreateUpstreamRequest(String name, String marketplaceUrl, String targetNamespace,
                String adapterType) {}

    record UpdateUpstreamRequest(Boolean enabled) {}

    /** SYNCING while the latest run is open; otherwise the source's last verdict. */
    private static String syncStatus(UpstreamSource source, SyncRun latestRun) {
        if (latestRun != null && "RUNNING".equals(latestRun.status())) {
            return "SYNCING";
        }
        if (source.lastSyncOk() == null) {
            return null;
        }
        return source.lastSyncOk() ? "OK" : "FAILED";
    }

    private static ReviewDtos.UpstreamDto toDto(UpstreamSource source, SyncRun latestRun) {
        boolean syncing = latestRun != null && "RUNNING".equals(latestRun.status());
        boolean completed = latestRun != null && !syncing;
        return new ReviewDtos.UpstreamDto(source.id().toString(), source.name(),
                source.marketplaceUrl(), source.targetNamespace(), source.adapterType(),
                source.enabled(),
                source.lastSyncAt() == null ? null : source.lastSyncAt().toString(),
                source.lastSyncOk(), source.lastError(),
                syncStatus(source, latestRun),
                latestRun == null ? null : latestRun.startedAt().toString(),
                completed ? latestRun.imported() : null,
                completed ? latestRun.skipped() : null,
                completed ? latestRun.failed() : null);
    }

    private static ReviewDtos.UpstreamSyncRunDto toRunDto(SyncRun run) {
        List<String> errors = run.errors() == null || run.errors().isBlank()
                ? List.of()
                : List.of(run.errors().split("\n"));
        return new ReviewDtos.UpstreamSyncRunDto(run.id().toString(),
                run.startedAt().toString(),
                run.finishedAt() == null ? null : run.finishedAt().toString(),
                run.imported(), run.skipped(), run.failed(), run.status(), errors);
    }
}
