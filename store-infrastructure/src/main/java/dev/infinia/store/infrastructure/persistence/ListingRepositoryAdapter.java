package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.contract.type.ListingVisibility;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.port.ListingQuery;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.infrastructure.persistence.entity.ListingEntity;
import dev.infinia.store.infrastructure.persistence.repository.ListingJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter for {@link ListingRepository}. Text search filters in memory over slug,
 * namespace, category, tags and localized names — portable across PostgreSQL and H2.
 * The PostgreSQL FTS upgrade path is tracked by ADR-008.
 */
@Component
public class ListingRepositoryAdapter implements ListingRepository {

    private static final int SCAN_CAP = 2000;

    private final ListingJpaRepository jpa;

    public ListingRepositoryAdapter(ListingJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Listing> findById(UUID id) {
        return jpa.findById(id).map(ListingRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<Listing> findByCoordinate(InfiniaCoordinate coordinate) {
        return jpa.findAllByOptionalType(coordinate.type.name()).stream()
                .filter(e -> coordinate.namespace.equals(e.namespace)
                        && coordinate.slug.equals(e.slug))
                .findFirst()
                .map(ListingRepositoryAdapter::toDomain);
    }

    @Override
    public List<Listing> findByIds(List<UUID> ids) {
        List<Listing> result = new ArrayList<>();
        for (ListingEntity e : jpa.findAllById(ids)) {
            result.add(toDomain(e));
        }
        return result;
    }

    @Override
    public boolean existsByNamespaceAndSlugAndType(String namespace, String slug, ListingType type) {
        return jpa.findAllByOptionalType(type.name()).stream()
                .anyMatch(e -> namespace.equals(e.namespace) && slug.equals(e.slug));
    }

    @Override
    public ListingQuery.ListingPage search(ListingQuery query) {
        List<ListingEntity> entities = jpa.findAllByOptionalType(
                query.type() == null ? null : query.type().name());
        String text = query.text() == null ? null : query.text().toLowerCase(Locale.ROOT).trim();

        List<Listing> matched = new ArrayList<>();
        for (ListingEntity e : entities) {
            if (!"PUBLIC".equals(e.visibility) || !"ACTIVE".equals(e.status)) {
                continue;
            }
            if (query.category() != null && !query.category().isBlank()
                    && !query.category().equalsIgnoreCase(e.category)) {
                continue;
            }
            if (text != null && !text.isEmpty() && !matchesText(e, text)) {
                continue;
            }
            if (query.featured() != null
                    && query.featured() != (e.featured != null && e.featured)) {
                continue;
            }
            matched.add(toDomain(e));
        }

        Comparator<Listing> comparator = switch (query.sort() == null
                ? ListingQuery.ListingSort.RELEVANCE : query.sort()) {
            case RECENT -> Comparator.comparing(l -> l.updatedAt, Comparator.reverseOrder());
            case FAVORITES -> Comparator.comparingLong((Listing l) -> l.favoriteCount).reversed();
            case DOWNLOADS, RELEVANCE -> Comparator.comparingLong((Listing l) -> l.downloads).reversed();
        };
        comparator = comparator.thenComparing(l -> l.id.toString());

        matched.sort(comparator);

        // Cursor seek: skip until the cursor tuple is passed.
        List<Listing> page = new ArrayList<>();
        boolean afterCursor = query.afterId() == null;
        for (Listing l : matched) {
            if (!afterCursor) {
                if (l.id.toString().equals(query.afterId())) {
                    afterCursor = true;
                }
                continue;
            }
            if (page.size() > query.limit()) {
                break;
            }
            page.add(l);
        }
        boolean hasMore = page.size() > query.limit();
        if (hasMore) {
            page = page.subList(0, query.limit());
        }
        Listing last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new ListingQuery.ListingPage(page, hasMore,
                last == null ? null : sortValue(query, last),
                last == null ? null : last.id.toString(), matched.size());
    }

    private static String sortValue(ListingQuery query, Listing l) {
        ListingQuery.ListingSort sort = query.sort() == null
                ? ListingQuery.ListingSort.RELEVANCE : query.sort();
        return switch (sort) {
            case RECENT -> l.updatedAt.toString();
            case FAVORITES, DOWNLOADS, RELEVANCE -> Long.toString(l.downloads);
        };
    }

    private static boolean matchesText(ListingEntity e, String text) {
        if (e.slug.contains(text) || e.namespace.contains(text)
                || (e.category != null && e.category.toLowerCase(Locale.ROOT).contains(text))) {
            return true;
        }
        for (String tag : e.tags) {
            if (tag.toLowerCase(Locale.ROOT).contains(text)) {
                return true;
            }
        }
        for (ListingEntity.LocalizationEmb loc : e.localizations.values()) {
            if (loc.name().toLowerCase(Locale.ROOT).contains(text)
                    || (loc.summary() != null && loc.summary().toLowerCase(Locale.ROOT).contains(text))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Listing> findByPublisher(UUID publisherUserId) {
        return jpa.findByPublisherUserId(publisherUserId).stream()
                .map(ListingRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<Listing> findAllForAdmin() {
        return jpa.findAll().stream().map(ListingRepositoryAdapter::toDomain).toList();
    }

    @Override
    @Transactional
    public void save(Listing listing) {
        ListingEntity entity = jpa.findById(listing.id).orElseGet(ListingEntity::new);
        copy(listing, entity);
        jpa.save(entity);
    }

    @Override
    @Transactional
    public void incrementDownloads(UUID listingId) {
        jpa.findById(listingId).ifPresent(e -> {
            e.downloads++;
            jpa.save(e);
        });
    }

    @Override
    @Transactional
    public void incrementFavorites(UUID listingId, long delta) {
        jpa.findById(listingId).ifPresent(e -> {
            e.favoriteCount = Math.max(0, e.favoriteCount + delta);
            jpa.save(e);
        });
    }

    private static void copy(Listing l, ListingEntity e) {
        e.id = l.id;
        e.namespaceId = l.namespaceId;
        e.namespace = l.namespace;
        e.slug = l.slug;
        e.type = l.type.name();
        e.visibility = l.visibility.name();
        e.status = l.status;
        e.category = l.category;
        e.tags = l.tags == null ? new ArrayList<>() : new ArrayList<>(l.tags);
        e.iconUrl = l.iconUrl;
        e.screenshots = l.screenshots == null ? new ArrayList<>() : new ArrayList<>(l.screenshots);
        e.defaultChannel = (l.defaultChannel == null ? Channel.STABLE : l.defaultChannel).name();
        e.publisherUserId = l.publisherUserId;
        e.organizationId = l.organizationId;
        e.downloads = l.downloads;
        e.favoriteCount = l.favoriteCount;
        e.featured = l.featured;
        e.createdAt = l.createdAt;
        e.updatedAt = l.updatedAt;
        e.localizations.clear();
        if (l.localizations != null) {
            for (Listing.Localization loc : l.localizations) {
                e.localizations.put(loc.locale(), new ListingEntity.LocalizationEmb(
                        loc.name(), loc.summary(), loc.descriptionMarkdown(), loc.changelogMarkdown()));
            }
        }
    }

    public static Listing toDomain(ListingEntity e) {
        Listing l = new Listing();
        l.id = e.id;
        l.namespaceId = e.namespaceId;
        l.namespace = e.namespace;
        l.slug = e.slug;
        l.type = ListingType.valueOf(e.type);
        l.visibility = ListingVisibility.valueOf(e.visibility);
        l.status = e.status;
        l.category = e.category;
        l.tags = e.tags == null ? new ArrayList<>() : new ArrayList<>(e.tags);
        l.iconUrl = e.iconUrl;
        l.screenshots = e.screenshots == null ? new ArrayList<>() : new ArrayList<>(e.screenshots);
        l.defaultChannel = Channel.valueOf(e.defaultChannel);
        l.publisherUserId = e.publisherUserId;
        l.organizationId = e.organizationId;
        l.downloads = e.downloads;
        l.favoriteCount = e.favoriteCount;
        l.featured = e.featured != null && e.featured;
        l.createdAt = e.createdAt;
        l.updatedAt = e.updatedAt;
        l.localizations = new ArrayList<>();
        e.localizations.forEach((locale, emb) -> l.localizations.add(new Listing.Localization(
                locale, emb.name(), emb.summary(), emb.descriptionMarkdown(), emb.changelogMarkdown())));
        return l;
    }
}
