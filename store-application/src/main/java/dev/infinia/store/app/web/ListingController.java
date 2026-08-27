package dev.infinia.store.app.web;

import dev.infinia.store.app.service.CatalogService;
import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.ModerationService;
import dev.infinia.store.contract.api.ListingDtos;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.ListingRating;
import dev.infinia.store.domain.model.Release;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Listing detail, ratings and abuse reports (design §10.2, §12.4). */
@RestController
@RequestMapping("/api/v1/listings")
public class ListingController {

    private final CatalogService catalog;
    private final ModerationService moderation;
    private final CurrentPrincipal principal;

    public ListingController(CatalogService catalog, ModerationService moderation,
            CurrentPrincipal principal) {
        this.catalog = catalog;
        this.moderation = moderation;
        this.principal = principal;
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

    @GetMapping("/{namespace}/{slug}/ratings")
    public ListingDtos.RatingsPageDto ratings(@PathVariable String namespace,
            @PathVariable String slug) {
        return moderation.ratingsOf(requireListing(namespace, slug));
    }

    /** Creates or updates the caller's rating; one per user per listing (design §12.4). */
    @PutMapping("/{namespace}/{slug}/ratings")
    public ListingDtos.RatingDto rate(@PathVariable String namespace, @PathVariable String slug,
            @RequestBody ListingDtos.UpsertRatingRequest request) {
        Listing listing = requireListing(namespace, slug);
        ListingRating rating = moderation.rate(principal.requireUserId(), listing,
                request.stars(), request.comment());
        return new ListingDtos.RatingDto(rating.id().toString(), rating.userId().toString(),
                rating.stars(), rating.comment(), rating.updatedAt().toString());
    }

    private Listing requireListing(String namespace, String slug) {
        Listing listing = catalog.findListing(namespace, slug);
        if (listing == null) {
            throw new dev.infinia.store.domain.DomainException(
                    dev.infinia.store.contract.error.StoreErrorCode.LISTING_NOT_FOUND,
                    "Listing not found: " + namespace + "/" + slug);
        }
        return listing;
    }
}
