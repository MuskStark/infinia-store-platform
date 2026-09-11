package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "membership_order")
public class MembershipOrderEntity {
    @Id
    public UUID id;
    @Column(name = "order_no", nullable = false)
    public String orderNo;
    @Column(name = "user_id", nullable = false)
    public UUID userId;
    @Column(name = "plan_id", nullable = false)
    public UUID planId;
    @Column(name = "target_level", nullable = false)
    public int targetLevel;
    @Column(name = "duration_days", nullable = false)
    public int durationDays;
    @Column(name = "price_fen", nullable = false)
    public long priceFen;
    @Column(name = "status", nullable = false)
    public String status;
    @Column(name = "channel", nullable = false)
    public String channel;
    @Column(name = "gateway_trade_no")
    public String gatewayTradeNo;
    @Column(name = "pay_url")
    public String payUrl;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "paid_at")
    public Instant paidAt;
    @Column(name = "closed_at")
    public Instant closedAt;
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
}
