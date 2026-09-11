package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.contract.type.MembershipOrderStatus;
import dev.infinia.store.domain.model.MembershipOrder;
import dev.infinia.store.domain.port.BillingRepositories;
import dev.infinia.store.infrastructure.persistence.entity.MembershipOrderEntity;
import dev.infinia.store.infrastructure.persistence.repository.MembershipOrderJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class MembershipOrderAdapter
        implements BillingRepositories.MembershipOrderRepository {

    private final MembershipOrderJpaRepository jpa;

    public MembershipOrderAdapter(MembershipOrderJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public Optional<MembershipOrder> findByOrderNoForUpdate(String orderNo) {
        return jpa.findByOrderNoForUpdate(orderNo).map(MembershipOrderAdapter::toDomain);
    }

    @Override
    public Optional<MembershipOrder> findByOrderNo(String orderNo) {
        return jpa.findByOrderNo(orderNo).map(MembershipOrderAdapter::toDomain);
    }

    @Override
    public Optional<MembershipOrder> findByGatewayTradeNo(String gatewayTradeNo) {
        return jpa.findByGatewayTradeNo(gatewayTradeNo).map(MembershipOrderAdapter::toDomain);
    }

    @Override
    public List<MembershipOrder> findByUserId(UUID userId) {
        return jpa.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(MembershipOrderAdapter::toDomain).toList();
    }

    @Override
    public List<MembershipOrder> findRecent(int limit) {
        return jpa.findTop200ByOrderByCreatedAtDesc().stream()
                .limit(limit)
                .map(MembershipOrderAdapter::toDomain).toList();
    }

    @Override
    public List<MembershipOrder> findPendingExpired(Instant now) {
        return jpa.findByStatusAndExpiresAtBefore(MembershipOrderStatus.PENDING.name(), now)
                .stream().map(MembershipOrderAdapter::toDomain).toList();
    }

    @Override
    @Transactional
    public void save(MembershipOrder order) {
        MembershipOrderEntity e = jpa.findById(order.id).orElseGet(MembershipOrderEntity::new);
        e.id = order.id;
        e.orderNo = order.orderNo;
        e.userId = order.userId;
        e.planId = order.planId;
        e.targetLevel = order.targetLevel;
        e.durationDays = order.durationDays;
        e.priceFen = order.priceFen;
        e.status = order.status.name();
        e.channel = order.channel;
        e.gatewayTradeNo = order.gatewayTradeNo;
        e.payUrl = order.payUrl;
        e.createdAt = order.createdAt;
        e.paidAt = order.paidAt;
        e.closedAt = order.closedAt;
        e.expiresAt = order.expiresAt;
        jpa.save(e);
    }

    @Override
    @Transactional
    public void updateExpiresAt(UUID orderId, Instant expiresAt) {
        jpa.updateExpiresAt(orderId, expiresAt);
    }

    @Override
    public long countByStatus(MembershipOrderStatus status) {
        return jpa.countByStatus(status.name());
    }

    static MembershipOrder toDomain(MembershipOrderEntity e) {
        MembershipOrder o = new MembershipOrder(e.id, e.orderNo, e.userId, e.planId,
                e.targetLevel, e.durationDays, e.priceFen,
                MembershipOrderStatus.valueOf(e.status), e.channel, e.createdAt, e.expiresAt);
        o.gatewayTradeNo = e.gatewayTradeNo;
        o.payUrl = e.payUrl;
        o.paidAt = e.paidAt;
        o.closedAt = e.closedAt;
        return o;
    }
}
