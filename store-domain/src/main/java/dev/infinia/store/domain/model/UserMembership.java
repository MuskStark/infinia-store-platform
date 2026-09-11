package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The user's time-limited purchased membership (one row per user). The
 * effective ladder position is {@code max(store_user.bee_level, this level)}
 * while {@code expiresAt} is in the future; the row stays after expiry so
 * renewals can extend from the old deadline and the admin console keeps history.
 */
public class UserMembership {
    public UUID userId;
    public int level;
    public Instant expiresAt;
    public Instant updatedAt;

    public UserMembership() {
    }

    public UserMembership(UUID userId, int level, Instant expiresAt, Instant updatedAt) {
        this.userId = userId;
        this.level = level;
        this.expiresAt = expiresAt;
        this.updatedAt = updatedAt;
    }

    public boolean active(Instant now) {
        return expiresAt != null && expiresAt.isAfter(now);
    }
}
