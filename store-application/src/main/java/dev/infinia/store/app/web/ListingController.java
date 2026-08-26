package dev.infinia.store.app.web;

import dev.infinia.store.app.service.CatalogService;
import dev.infinia.store.contract.api.ListingDtos;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Release;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Listing detail endpoint (design §10.2). */
@RestController
@RequestMapping("/api/v1/listings")
public class ListingController {

    private final CatalogService catalog;

    public ListingController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/{namespace}/{slug}")
    public ResponseEntity<ListingDtos.ListingDetailDto> detail(@PathVariable String namespace,
            @PathVariable String slug,
            @RequestParam(required = false) String channel) {
        Listing listing = catalog.findListing(namespace, slug);
        if (listing == null) {
            throw new dev.infinia.store.domain.DomainException(
                    dev.infinia.store.contract.error.StoreErrorCode.LISTING_NOT_FOUND,
                    "Listing not found: " + namespace + "/" + slug);
        }
        Channel channelFilter = channel == null ? null
                : Channel.valueOf(channel.trim().toUpperCase());
        List<Release> releases = catalog.visibleReleases(listing, channelFilter);
        return ResponseEntity.ok().eTag("\"listing-" + listing.id + "-" + listing.updatedAt + "\"")
                .body(DtoMapper.listingDetail(listing, releases, listing.favoriteCount));
    }
}
