package dev.infinia.store.contract.api;

import java.util.List;

/** DTOs for the public catalog (design §10.2). */
public final class CatalogDtos {

    private CatalogDtos() {}

    public record CatalogItemDto(
            String coordinate,
            String type,
            String namespace,
            String slug,
            String name,
            String summary,
            String category,
            List<String> tags,
            String iconUrl,
            String latestVersion,
            String channel,
            long downloads,
            String publisherName,
            String updatedAt,
            boolean featured,
            int minBeeLevel) {
    }

    public record CatalogPageDto(
            List<CatalogItemDto> items,
            String nextCursor,
            long totalEstimate) {
    }
}
