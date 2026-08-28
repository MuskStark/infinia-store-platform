package dev.infinia.store.domain.model;

import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.contract.type.ListingVisibility;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A catalog listing — one of the five artifact classes sharing the unified catalog
 * model (design §6.1).
 */
public class Listing {
    public UUID id;
    public UUID namespaceId;
    /** Denormalized namespace name for cheap catalog rendering. */
    public String namespace;
    public String slug;
    public ListingType type;
    public ListingVisibility visibility;
    /** ACTIVE | ARCHIVED */
    public String status;
    public String category;
    public List<String> tags = new ArrayList<>();
    public String iconUrl;
    public List<String> screenshots = new ArrayList<>();
    public Channel defaultChannel = Channel.STABLE;
    public UUID publisherUserId;
    public UUID organizationId;
    public long downloads;
    public long favoriteCount;
    /** Editorial featuring toggled by platform admins (design §12.4). */
    public boolean featured;
    public Instant createdAt;
    public Instant updatedAt;
    public List<Localization> localizations = new ArrayList<>();

    /** Localized presentation text (design §6.1 listing_i18n). */
    public record Localization(String locale, String name, String summary,
            String descriptionMarkdown, String changelogMarkdown) {}

    public InfiniaCoordinate coordinate() {
        return InfiniaCoordinate.of(type, namespace, slug);
    }

    /** Display name for the requested locale, falling back to English then any. */
    public String name(String locale) {
        String target = locale == null ? "en" : locale.toLowerCase(Locale.ROOT);
        String english = null;
        String any = null;
        for (Localization l : localizations) {
            if (l.locale().equalsIgnoreCase(target)) {
                return l.name();
            }
            if ("en".equalsIgnoreCase(l.locale()) && english == null) {
                english = l.name();
            }
            if (any == null) {
                any = l.name();
            }
        }
        return english != null ? english : (any != null ? any : slug);
    }

    public String summary(String locale) {
        String target = locale == null ? "en" : locale.toLowerCase(Locale.ROOT);
        for (Localization l : localizations) {
            if (l.locale().equalsIgnoreCase(target)) {
                return l.summary();
            }
        }
        for (Localization l : localizations) {
            if ("en".equalsIgnoreCase(l.locale())) {
                return l.summary();
            }
        }
        return localizations.isEmpty() ? "" : localizations.get(0).summary();
    }

    public boolean isPubliclyVisible() {
        return visibility == ListingVisibility.PUBLIC && "ACTIVE".equals(status);
    }
}
