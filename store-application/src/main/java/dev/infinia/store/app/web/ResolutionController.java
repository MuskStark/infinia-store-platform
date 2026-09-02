package dev.infinia.store.app.web;

import dev.infinia.store.app.service.BeeLevelService;
import dev.infinia.store.app.service.CatalogService;
import dev.infinia.store.contract.api.ListingDtos;
import dev.infinia.store.contract.api.ResolutionDtos;
import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.service.DependencySolver;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Version and dependency resolution (design §10.2, §9.4). */
@RestController
@RequestMapping("/api/v1")
public class ResolutionController {

    private final CatalogService catalog;
    private final ListingRepository listings;
    private final BeeLevelService beeLevels;

    public ResolutionController(CatalogService catalog, ListingRepository listings,
            BeeLevelService beeLevels) {
        this.catalog = catalog;
        this.listings = listings;
        this.beeLevels = beeLevels;
    }

    @PostMapping("/resolutions")
    public ResolutionDtos.ResolveResponseDto resolve(@RequestBody ResolveBody body) {
        if (body == null || body.coordinate() == null || body.client() == null
                || body.client().hostVersion() == null) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "coordinate and client.hostVersion are required");
        }
        InfiniaCoordinate root;
        try {
            root = InfiniaCoordinate.parse(body.coordinate());
        } catch (IllegalArgumentException e) {
            throw new DomainException(StoreErrorCode.INVALID_COORDINATE, e.getMessage());
        }
        // Infinia Level gate on the requested root: below-threshold viewers get a
        // concrete bee_level_required problem instead of a missing-dependency maze.
        listings.findByCoordinate(root).ifPresent(beeLevels::requireListingAccess);
        Map<String, String> installed = new LinkedHashMap<>();
        if (body.client().installed() != null) {
            for (ResolutionDtos.InstalledRef ref : body.client().installed()) {
                installed.put(ref.coordinate(), ref.version());
            }
        }
        DependencySolver.Result result = catalog.resolve(root, body.range(),
                body.client().hostVersion(), body.client().os(), body.client().arch(),
                body.client().channel() == null ? null
                        : Channel.valueOf(body.client().channel().toUpperCase()),
                installed);

        List<ResolutionDtos.ResolutionItemDto> plan = result.plan().stream()
                .map(r -> new ResolutionDtos.ResolutionItemDto(
                        r.candidate().coordinate().toString(),
                        r.candidate().releaseId(),
                        r.candidate().version().toString(),
                        r.candidate().channel().name().toLowerCase(),
                        r.candidate().artifacts().stream().map(DtoMapper::artifact).toList(),
                        r.candidate().permissions().stream()
                                .map(p -> new ListingDtos.PermissionDto(p.permissionId(),
                                        p.scope(), p.required(), p.reason()))
                                .toList(),
                        r.candidate().requiresHost(),
                        r.alreadyInstalled(),
                        r.reason()))
                .toList();
        List<ResolutionDtos.MissingDependencyDto> missing = result.missing().stream()
                .map(m -> new ResolutionDtos.MissingDependencyDto(m.coordinate(), m.range(),
                        m.reason()))
                .toList();
        return new ResolutionDtos.ResolveResponseDto(result.resolvable(),
                root.listingPart().toString(), plan, missing, Instant.now().toString());
    }

    /** Request body mirror of ResolveRequest without bean-validation coupling. */
    public record ResolveBody(String coordinate, String range, ClientBody client) {}

    public record ClientBody(String hostVersion, String os, String arch, String channel,
            List<ResolutionDtos.InstalledRef> installed) {}
}
