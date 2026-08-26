package dev.infinia.store.app.web;

import dev.infinia.store.app.service.CatalogService;
import dev.infinia.store.contract.api.CatalogDtos;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.domain.port.ListingQuery;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public catalog browsing — anonymous access allowed (design §10.2, §13.2). */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/catalog")
    public ResponseEntity<CatalogDtos.CatalogPageDto> browse(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String hostVersion,
            @RequestParam(required = false) String os,
            @RequestParam(required = false) String arch,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "24") int limit) {
        CatalogDtos.CatalogPageDto page = catalog.browse(new CatalogService.BrowseQuery(
                type == null || type.isBlank() ? null
                        : ListingType.valueOf(type.trim().toUpperCase()),
                query,
                category,
                channel == null ? null : Channel.valueOf(channel.trim().toUpperCase()),
                hostVersion,
                os,
                arch,
                sort == null ? null : ListingQuery.ListingSort.valueOf(sort.trim().toUpperCase()),
                cursor,
                Math.min(Math.max(limit, 1), 100)));
        String etag = "\"catalog-" + Integer.toHexString(page.hashCode()) + "\"";
        return ResponseEntity.ok().eTag(etag).header("Cache-Control", "public, max-age=30")
                .body(page);
    }
}
