package dev.infinia.store.domain.port;

import dev.infinia.store.contract.type.BeeLevel;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.domain.model.Listing;

import java.util.List;

/** Catalog search parameters plus cursor-seek tuple (design §10.1 cursor pagination). */
public record ListingQuery(ListingType type, String text, String category, Channel channel,
        String publisherUserId, ListingSort sort, String afterSortValue, String afterId,
        Boolean featured, int viewerBeeLevel, int limit) {

    /** Compatibility constructor without the bee-level filter. */
    public ListingQuery(ListingType type, String text, String category, Channel channel,
            String publisherUserId, ListingSort sort, String afterSortValue, String afterId,
            Boolean featured, int limit) {
        this(type, text, category, channel, publisherUserId, sort, afterSortValue, afterId,
                featured, BeeLevel.MAX_LEVEL, limit);
    }

    public enum ListingSort {
        RELEVANCE, RECENT, DOWNLOADS, FAVORITES;

        private static final String VALID_VALUES = java.util.Arrays.stream(values())
                .map(s -> s.name().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(", "));

        /**
         * Parses a wire-format sort key (case-insensitive). Unlike
         * {@link #valueOf} the failure message lists the accepted values, so a
         * bad {@code ?sort=} query parameter surfaces as an actionable 400
         * problem detail instead of a raw "No enum constant" error.
         */
        public static ListingSort parse(String input) {
            if (input == null || input.isBlank()) {
                throw new IllegalArgumentException("sort must be one of: " + VALID_VALUES);
            }
            try {
                return valueOf(input.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown sort '" + input.trim() + "' — must be one of: " + VALID_VALUES);
            }
        }
    }

    public record ListingPage(List<Listing> items, boolean hasMore, String lastSortValue,
            String lastId, long totalEstimate) {}
}
