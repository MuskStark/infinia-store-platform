package dev.infinia.store.contract.api;

import java.util.List;

/** DTOs for the Infinia Level purchase flow (membership plans, orders and payment callbacks). */
public final class MembershipDtos {

    private MembershipDtos() {}

    /** A purchasable membership plan: one Infinia Level tier for a fixed duration. */
    public record MembershipPlanDto(
            String planId,
            int beeLevel,
            int durationDays,
            long priceFen,
            boolean active,
            int sort) {
    }

    /**
     * The caller's ladder position: the permanent admin-granted base level, the
     * time-limited purchased membership (when active) and the effective level the
     * store enforces — {@code max(base, active membership level)}.
     */
    public record MembershipStatusDto(
            int baseBeeLevel,
            int effectiveBeeLevel,
            Integer membershipLevel,
            String membershipExpiresAt,
            List<String> channels) {
    }

    public record CreateMembershipOrderRequest(String planId, String channel) {
    }

    /**
     * A created order plus the gateway cashier URL to send the buyer to. The
     * cashier URL is one-shot and short-lived; a fresh order is the retry path.
     */
    public record MembershipOrderDto(
            String orderNo,
            int targetLevel,
            int durationDays,
            long priceFen,
            String status,
            String channel,
            String payUrl,
            String createdAt,
            String paidAt,
            String expiresAt) {
    }

    /** Full plan record for the admin console (includes inactive plans). */
    public record AdminMembershipPlanDto(
            String planId,
            int beeLevel,
            int durationDays,
            long priceFen,
            boolean active,
            int sort,
            String externalUrl,
            String createdAt,
            String updatedAt) {
    }

    /** Create/update plan request; update is partial (omitted fields keep values). */
    public record AdminPlanRequest(
            Integer beeLevel,
            Integer durationDays,
            Long priceFen,
            Boolean active,
            Integer sort,
            String externalUrl) {
    }

    /** Admin-console view of one purchase order. */
    public record AdminMembershipOrderDto(
            String orderNo,
            String userId,
            String email,
            String displayName,
            int targetLevel,
            int durationDays,
            long priceFen,
            String status,
            String channel,
            String gatewayTradeNo,
            String createdAt,
            String paidAt,
            String expiresAt) {
    }
}
