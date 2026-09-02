package dev.infinia.store.domain.model;

import dev.infinia.store.contract.type.UserRole;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Store account principal (design §7.1). Cloud identity is separate from local data ownership. */
public class StoreUser {
    public UUID id;
    public String email;
    public String emailNormalized;
    public String displayName;
    public Set<UserRole> roles;
    /** ACTIVE | DISABLED */
    public String status;
    /**
     * Bee ladder position (蜜蜂等级, 0=LARVA..4=QUEEN) — the store's user-level
     * identity. Listings may gate view/download behind a minimum bee level.
     */
    public int beeLevel;
    public boolean mfaEnabled;
    public Instant createdAt;
    public Instant lastLoginAt;

    public StoreUser() {
    }

    public StoreUser(UUID id, String email, String emailNormalized, String displayName,
            Set<UserRole> roles, String status, Instant createdAt) {
        this(id, email, emailNormalized, displayName, roles, status,
                dev.infinia.store.contract.type.BeeLevel.LARVA.level, createdAt);
    }

    public StoreUser(UUID id, String email, String emailNormalized, String displayName,
            Set<UserRole> roles, String status, int beeLevel, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.emailNormalized = emailNormalized;
        this.displayName = displayName;
        this.roles = roles;
        this.status = status;
        this.beeLevel = beeLevel;
        this.createdAt = createdAt;
    }

    public boolean hasRole(UserRole role) {
        return roles != null && roles.contains(role);
    }
}
