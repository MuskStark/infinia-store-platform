package dev.infinia.store.domain.model;

import dev.infinia.store.contract.type.MembershipOrderStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One membership purchase attempt: the plan's terms are snapshotted onto the
 * order (level, duration, price) so plan edits never mutate in-flight history.
 * The order moves PENDING → PAID on a verified gateway callback, or PENDING →
 * CLOSED when the payment window ({@code expiresAt}) lapses; a late callback
 * for a CLOSED order still transitions it to PAID because the money moved.
 */
public class MembershipOrder {
    public UUID id;
    /** Merchant order number sent to the gateway (trade_order_id), unique. */
    public String orderNo;
    public UUID userId;
    public UUID planId;
    public int targetLevel;
    public int durationDays;
    public long priceFen;
    public MembershipOrderStatus status;
    /** Gateway channel, e.g. WECHAT | ALIPAY (informational; credentials pick the app). */
    public String channel;
    /** Gateway-side trade number (transaction_id) captured from the callback. */
    public String gatewayTradeNo;
    /** Cashier URL returned by the gateway; one-shot and short-lived. */
    public String payUrl;
    public Instant createdAt;
    public Instant paidAt;
    public Instant closedAt;
    /** End of the payment window — unpaid orders close after this. */
    public Instant expiresAt;

    public MembershipOrder() {
    }

    public MembershipOrder(UUID id, String orderNo, UUID userId, UUID planId, int targetLevel,
            int durationDays, long priceFen, MembershipOrderStatus status, String channel,
            Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.orderNo = orderNo;
        this.userId = userId;
        this.planId = planId;
        this.targetLevel = targetLevel;
        this.durationDays = durationDays;
        this.priceFen = priceFen;
        this.status = status;
        this.channel = channel;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }
}
