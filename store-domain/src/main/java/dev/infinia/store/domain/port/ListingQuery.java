package dev.infinia.store.domain.port;

import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.domain.model.Listing;

import java.util.List;

/** Catalog search parameters plus cursor-seek tuple (design §10.1 cursor pagination). */
public record ListingQuery(ListingType type, String text, String category, Channel channel,
        String publisherUserId, ListingSort sort, String afterSortValue, String afterId,
        Boolean featured, int limit) {

    /** Compatibility constructor without the curation filter. */
    public ListingQuery(ListingType type, String text, String category, Channel channel,
            String publisherUserId, ListingSort sort, String afterSortValue, String afterId,
            int limit) {
        this(type, text, category, channel, publisherUserId, sort, afterSortValue, afterId,
                null, limit);
    }

    public enum ListingSort { RELEVANCE, RECENT, DOWNLOADS, FAVORITES }

    public record ListingPage(List<Listing> items, boolean hasMore, String lastSortValue, String lastId) {}
}
