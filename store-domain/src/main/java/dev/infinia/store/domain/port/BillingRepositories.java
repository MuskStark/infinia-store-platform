package dev.infinia.store.domain.port;

import dev.infinia.store.contract.type.MembershipOrderStatus;
import dev.infinia.store.domain.model.MembershipOrder;
import dev.infinia.store.domain.model.MembershipPlan;
import dev.infinia.store.domain.model.UserMembership;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing ports for the Infinia Level purchase flow (会员等级购买): admin-managed
 * plans, purchase orders and per-user time-limited memberships. Implementations
 * live in store-infrastructure.
 */
public final class BillingRepositories {

    private BillingRepositories() {}

    public interface MembershipPlanRepository {
        /** Every plan (active and inactive), display order first. */
        List<MembershipPlan> findAll();

        Optional<MembershipPlan> findById(UUID id);

        void save(MembershipPlan plan);

        void delete(UUID id);
    }

    public interface MembershipOrderRepository {
        /** Locking read for callback handling so concurrent notifies apply once. */
        Optional<MembershipOrder> findByOrderNoForUpdate(String orderNo);

        Optional<MembershipOrder> findByOrderNo(String orderNo);

        /** Idempotency probe for webhook-driven gateways (e.g. BMAC transaction ids). */
        Optional<MembershipOrder> findByGatewayTradeNo(String gatewayTradeNo);

        /** The user's orders, newest first. */
        List<MembershipOrder> findByUserId(UUID userId);

        /** Newest orders across all users — the admin console. */
        List<MembershipOrder> findRecent(int limit);

        /** PENDING orders whose payment window lapsed — the closing job. */
        List<MembershipOrder> findPendingExpired(Instant now);

        void save(MembershipOrder order);

        /** Test/admin backdoor: shift an order's window to simulate ageing. */
        void updateExpiresAt(UUID orderId, Instant expiresAt);

        long countByStatus(MembershipOrderStatus status);
    }

    public interface UserMembershipRepository {
        Optional<UserMembership> findByUserId(UUID userId);

        void save(UserMembership membership);
    }
}
