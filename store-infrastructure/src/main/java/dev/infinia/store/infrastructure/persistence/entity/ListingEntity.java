package dev.infinia.store.infrastructure.persistence.entity;

import dev.infinia.store.infrastructure.persistence.converter.StringListConverter;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "listing")
public class ListingEntity {
    @Id
    public UUID id;
    @Column(name = "namespace_id", nullable = false)
    public UUID namespaceId;
    @Column(name = "namespace", nullable = false)
    public String namespace;
    @Column(name = "slug", nullable = false)
    public String slug;
    @Column(name = "type", nullable = false)
    public String type;
    @Column(name = "visibility", nullable = false)
    public String visibility;
    @Column(name = "status", nullable = false)
    public String status;
    @Column(name = "category")
    public String category;
    @Convert(converter = StringListConverter.class)
    @Column(name = "tags", length = 1000)
    public List<String> tags = new ArrayList<>();
    @Column(name = "icon_url")
    public String iconUrl;
    @Convert(converter = StringListConverter.class)
    @Column(name = "screenshots", length = 4000)
    public List<String> screenshots = new ArrayList<>();
    @Column(name = "default_channel", nullable = false)
    public String defaultChannel;
    @Column(name = "publisher_user_id", nullable = false)
    public UUID publisherUserId;
    @Column(name = "organization_id")
    public UUID organizationId;
    @Column(name = "downloads", nullable = false)
    public long downloads;
    @Column(name = "favorite_count", nullable = false)
    public long favoriteCount;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /** Maps to listing_i18n (design §6.1). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "listing_i18n", joinColumns = @JoinColumn(name = "listing_id"))
    @MapKeyColumn(name = "locale", length = 8)
    public Map<String, LocalizationEmb> localizations = new HashMap<>();

    @Embeddable
    public static record LocalizationEmb(String name, String summary,
            @Column(name = "description_md") String descriptionMarkdown,
            @Column(name = "changelog_md") String changelogMarkdown) {
    }
}
