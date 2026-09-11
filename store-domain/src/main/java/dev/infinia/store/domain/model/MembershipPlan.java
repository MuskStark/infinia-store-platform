package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A purchasable membership plan (会员等级套餐): one Infinia Level tier held for a
 * fixed duration at a fixed price. Plans are admin-managed at runtime; orders
 * snapshot the terms at purchase time so later edits never rewrite history.
 * {@code externalUrl} optionally points at a hosted checkout product (e.g. a
 * Buy Me a Coffee Extra) — the buyer is sent there instead of a gateway
 * cashier, and the webhook matches the payment back by email + amount.
 */
public class MembershipPlan {
    public UUID id;
    /** Target Infinia Level (1=WORKER..4=QUEEN); LARVA is not purchasable. */
    public int beeLevel;
    /** How many days the purchased membership stays active. */
    public int durationDays;
    /** Price in fen (分) — integer money; BMAC plans price in the creator currency's cents. */
    public long priceFen;
    /** Inactive plans stay browsable in the admin console but cannot be bought. */
    public boolean active;
    /** Display order within the same level, ascending. */
    public int sort;
    public String externalUrl;
    public Instant createdAt;
    public Instant updatedAt;

    public MembershipPlan() {
    }

    public MembershipPlan(UUID id, int beeLevel, int durationDays, long priceFen,
            boolean active, int sort, String externalUrl, Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.beeLevel = beeLevel;
        this.durationDays = durationDays;
        this.priceFen = priceFen;
        this.active = active;
        this.sort = sort;
        this.externalUrl = externalUrl;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
