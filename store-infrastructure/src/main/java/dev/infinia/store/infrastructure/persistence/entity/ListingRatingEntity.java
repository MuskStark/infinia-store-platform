package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_rating")
public class ListingRatingEntity {
    @Id
    public UUID id;
    @Column(name = "listing_id", nullable = false)
    public UUID listingId;
    @Column(name = "user_id", nullable = false)
    public UUID userId;
    @Column(nullable = false)
    public Short stars;
    @Column(length = 2000)
    public String comment;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
