package dev.infinia.store.app.service;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.contract.api.MembershipDtos;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.contract.type.BeeLevel;
import dev.infinia.store.contract.type.MembershipOrderStatus;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.MembershipOrder;
import dev.infinia.store.domain.model.MembershipPlan;
import dev.infinia.store.domain.model.StoreUser;
import dev.infinia.store.domain.model.UserMembership;
import dev.infinia.store.domain.port.BillingRepositories;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.port.PaymentGateway;
import dev.infinia.store.domain.service.UuidV7;
import dev.infinia.store.infrastructure.payment.BmacProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Infinia Level purchase flow (会员等级购买): admin-managed plans, gateway-backed
 * orders and time-limited memberships layered on top of the permanent
 * admin-granted ladder. The effective level is
 * {@code max(store_user.bee_level, active user_membership.level)} — purchases
 * never lower a granted level and expire lazily, so no demotion job exists.
 */
@Service
public class MembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final BillingRepositories.MembershipPlanRepository plans;
    private final BillingRepositories.MembershipOrderRepository orders;
    private final BillingRepositories.UserMembershipRepository memberships;
    private final IdentityRepositories.UserRepository users;
    private final PaymentGateway gateway;
    private final StoreProperties properties;
    private final AuditService audit;
    private final BmacProperties bmac;
    private final ObjectMapper mapper;

    public MembershipService(BillingRepositories.MembershipPlanRepository plans,
            BillingRepositories.MembershipOrderRepository orders,
            BillingRepositories.UserMembershipRepository memberships,
            IdentityRepositories.UserRepository users,
            PaymentGateway gateway, StoreProperties properties, AuditService audit,
            BmacProperties bmac, ObjectMapper mapper) {
        this.plans = plans;
        this.orders = orders;
        this.memberships = memberships;
        this.users = users;
        this.gateway = gateway;
        this.properties = properties;
        this.audit = audit;
        this.bmac = bmac;
        this.mapper = mapper;
    }

    // ---- buyer surface ----

    /** Active plans for the purchase page (public pricing). */
    public List<MembershipDtos.MembershipPlanDto> activePlans() {
        return plans.findAll().stream().filter(p -> p.active).map(MembershipService::planDto)
                .toList();
    }

    /** The caller's ladder position and the channels they can pay through. */
    public MembershipDtos.MembershipStatusDto status(UUID userId) {
        StoreUser user = users.findById(userId).orElseThrow(
                () -> new DomainException(StoreErrorCode.NOT_FOUND, "User not found"));
        UserMembership membership = memberships.findByUserId(userId).orElse(null);
        boolean active = membership != null && membership.active(Instant.now());
        return new MembershipDtos.MembershipStatusDto(
                user.beeLevel,
                effectiveLevel(user.beeLevel, membership, Instant.now()),
                active ? membership.level : null,
                active ? membership.expiresAt.toString() : null,
                gateway.supportedChannels());
    }

    /**
     * Creates a PENDING order and a gateway cashier session. The plan's terms are
     * snapshotted onto the order. Purchases must climb the ladder (or renew the
     * active tier); a level at or below the buyer's current effective level is
     * refused so nobody pays for what they already hold.
     */
    @Transactional
    public MembershipDtos.MembershipOrderDto createOrder(UUID userId,
            MembershipDtos.CreateMembershipOrderRequest request) {
        if (request == null || request.planId() == null) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED, "planId is required");
        }
        MembershipPlan plan = plans.findById(UUID.fromString(request.planId())).orElseThrow(
                () -> new DomainException(StoreErrorCode.MEMBERSHIP_PLAN_NOT_FOUND,
                        "No membership plan: " + request.planId()));
        if (!plan.active) {
            throw new DomainException(StoreErrorCode.MEMBERSHIP_PLAN_INACTIVE,
                    "Plan " + plan.id + " is not on sale");
        }
        List<String> channels = gateway.supportedChannels();
        if (channels.isEmpty()) {
            throw new DomainException(StoreErrorCode.PAYMENT_NOT_CONFIGURED,
                    "No payment gateway credentials configured (store.pay.xunhu.*)");
        }
        String channel = request.channel() == null ? channels.get(0)
                : request.channel().trim().toUpperCase(Locale.ROOT);
        if (!channels.contains(channel)) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "Unknown channel " + channel + " (available: " + channels + ")");
        }

        StoreUser user = users.findById(userId).orElseThrow(
                () -> new DomainException(StoreErrorCode.NOT_FOUND, "User not found"));
        UserMembership membership = memberships.findByUserId(userId).orElse(null);
        Instant now = Instant.now();
        boolean membershipActive = membership != null && membership.active(now);
        int effective = effectiveLevel(user.beeLevel, membership, now);
        boolean renewal = membershipActive && membership.level == plan.beeLevel
                && user.beeLevel < plan.beeLevel;
        if (!renewal && plan.beeLevel <= effective) {
            throw new DomainException(StoreErrorCode.MEMBERSHIP_PURCHASE_LEVEL_TOO_LOW,
                    "Purchased level " + BeeLevel.of(plan.beeLevel) + " (" + plan.beeLevel
                            + ") is not above the account's effective level " + effective,
                    Map.of("targetBeeLevel", plan.beeLevel,
                            "currentEffectiveBeeLevel", effective));
        }

        Instant expiresAt = now.plus(Duration.ofMinutes(properties.pay().orderExpireMinutes()));
        MembershipOrder order = new MembershipOrder(UuidV7.generate(), newOrderNo(), userId,
                plan.id, plan.beeLevel, plan.durationDays, plan.priceFen,
                MembershipOrderStatus.PENDING, channel, now, expiresAt);
        orders.save(order);
        audit.record("USER", userId.toString(), "membership.orderCreated", "MEMBERSHIP_ORDER",
                order.orderNo, null, "L" + plan.beeLevel + "/" + plan.durationDays + "d/"
                        + plan.priceFen + "fen/" + channel, null);

        PaymentGateway.PaymentCreated created = gateway.createPayment(new PaymentGateway.PaymentRequest(
                order.orderNo, order.priceFen, orderTitle(order), channel,
                properties.baseUrl() + gateway.notifyPath(),
                properties.baseUrl() + "/membership/result?orderNo=" + order.orderNo,
                plan.externalUrl));
        order.payUrl = created.payUrl();
        orders.save(order);
        return orderDto(order);
    }

    /** The buyer's order; PENDING orders past their window close lazily here. */
    @Transactional
    public MembershipDtos.MembershipOrderDto getOrder(UUID userId, String orderNo) {
        MembershipOrder order = orders.findByOrderNo(orderNo).orElseThrow(
                () -> new DomainException(StoreErrorCode.MEMBERSHIP_ORDER_NOT_FOUND,
                        "No membership order: " + orderNo));
        if (!order.userId.equals(userId)) {
            throw new DomainException(StoreErrorCode.FORBIDDEN,
                    "Order " + orderNo + " belongs to another account");
        }
        closeIfLapsed(order, Instant.now());
        return orderDto(order);
    }

    /**
     * Consumes a gateway callback. Returns true when the gateway should stop
     * retrying (the literal {@code success} response): invalid signatures and
     * unknown orders return false so the operator's audit trail and the gateway's
     * retry log both surface the mismatch; already-PAID orders and non-payment
     * statuses are acknowledged without re-applying.
     */
    @Transactional
    public boolean handleNotify(Map<String, String> params) {
        PaymentGateway.NotifyResult result = gateway.parseNotify(params);
        if (!result.verified()) {
            log.warn("Payment notify failed signature verification: {}", params);
            return false;
        }
        if (!result.paid()) {
            // Refund-state notifications are acknowledged and ignored: the
            // purchased level stays until a human decides otherwise.
            return true;
        }
        MembershipOrder order = orders.findByOrderNoForUpdate(result.orderNo()).orElse(null);
        if (order == null) {
            log.warn("Payment notify for unknown order {}", result.orderNo());
            return false;
        }
        if (result.amountFen() != order.priceFen) {
            audit.record("GATEWAY", "xunhupay", "membership.amountMismatch", "MEMBERSHIP_ORDER",
                    order.orderNo, order.priceFen + "fen", result.amountFen() + "fen", null);
            log.error("Payment amount mismatch on {}: charged {}fen, expected {}fen",
                    order.orderNo, result.amountFen(), order.priceFen);
            return false;
        }
        if (order.status == MembershipOrderStatus.PAID) {
            return true; // replayed callback — already applied under the row lock
        }

        Instant now = Instant.now();
        order.status = MembershipOrderStatus.PAID;
        order.paidAt = now;
        order.gatewayTradeNo = result.gatewayTradeNo();
        applyMembership(order, now);
        orders.save(order);
        UserMembership membership = memberships.findByUserId(order.userId).orElse(null);
        audit.record("USER", order.userId.toString(), "membership.purchased", "MEMBERSHIP_ORDER",
                order.orderNo, null,
                "L" + order.targetLevel + "/" + order.durationDays + "d"
                        + (membership == null ? "" : " expires "
                                + membership.expiresAt), null);
        return true;
    }

    /**
     * Consumes a Buy Me a Coffee webhook (raw JSON body + {@code x-signature-sha256}
     * header). Returns true when BMAC should consider the delivery done — which
     * includes events we deliberately ignore (refunds, subscription lifecycle,
     * dashboard test events), because replying "fail" only burns their four
     * retries and eventually auto-disables the webhook. False (delivery error)
     * is reserved for a bad signature, which retrying will never fix either but
     * should stay loud in the dashboard.
     *
     * <p>Matching: BMAC cannot carry order metadata, so a paid
     * {@code donation.created}/{@code extra_purchase.created} is matched to the
     * buyer's most recent PENDING order created within the match window whose
     * price equals the paid amount ({@code amount × 100}, creator-currency
     * cents — plans for BMAC price accordingly). No exact match → audited and
     * acknowledged (a human resolves it from the console); replayed transaction
     * ids are idempotent.</p>
     */
    @Transactional
    public boolean handleBmacWebhook(String rawBody, String signature) {
        if (!bmac.configured()) {
            return false;
        }
        if (!bmacSignatureValid(rawBody, signature)) {
            log.warn("BMAC webhook failed signature verification");
            return false;
        }
        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("BMAC webhook body is not JSON: {}", e.getMessage());
            return false;
        }
        String type = event.path("type").asText("");
        if (!"donation.created".equals(type) && !"extra_purchase.created".equals(type)) {
            // Refunds/subscription lifecycle/shop orders: keep the level a human
            // sold; acknowledge so BMAC stops retrying.
            log.debug("BMAC webhook {} acknowledged without action", type);
            return true;
        }
        if (!event.path("live_mode").asBoolean(false)) {
            // Dashboard "Send test event" must never grant a real membership.
            log.info("BMAC test event ignored (live_mode=false)");
            return true;
        }
        JsonNode data = event.path("data");
        if (!"succeeded".equals(data.path("status").asText(""))) {
            return true;
        }
        String email = AccountService.normalizeEmail(data.path("supporter_email").asText(""));
        long amountFen = Math.round(data.path("amount").asDouble(0) * 100);
        String ref = "BMAC-" + data.path("transaction_id").asText(
                String.valueOf(data.path("id").asLong(0)));
        if (email.isBlank() || amountFen <= 0) {
            log.warn("BMAC webhook {} without supporter_email/amount", type);
            return true;
        }
        if (orders.findByGatewayTradeNo(ref).isPresent()) {
            return true; // replayed delivery — already applied
        }

        StoreUser buyer = users.findByEmailNormalized(email).orElse(null);
        MembershipOrder matched = null;
        if (buyer != null) {
            Instant windowStart = Instant.now()
                    .minus(Duration.ofMinutes(bmac.matchWindowMinutes()));
            matched = orders.findByUserId(buyer.id).stream()
                    .filter(o -> o.status == MembershipOrderStatus.PENDING)
                    .filter(o -> o.createdAt.isAfter(windowStart))
                    .filter(o -> o.priceFen == amountFen)
                    .findFirst().orElse(null);
        }
        if (matched == null) {
            audit.record("GATEWAY", "bmac", "membership.bmacUnmatched", "MEMBERSHIP_ORDER",
                    ref, null, email + "/" + amountFen + "fen/" + type, null);
            log.warn("BMAC payment {} from {} ({}fen) matched no pending order", ref, email,
                    amountFen);
            return true;
        }

        Instant now = Instant.now();
        matched.status = MembershipOrderStatus.PAID;
        matched.paidAt = now;
        matched.gatewayTradeNo = ref;
        applyMembership(matched, now);
        orders.save(matched);
        UserMembership membership = memberships.findByUserId(matched.userId).orElse(null);
        audit.record("USER", matched.userId.toString(), "membership.purchased",
                "MEMBERSHIP_ORDER", matched.orderNo, null,
                "L" + matched.targetLevel + "/" + matched.durationDays + "d"
                        + (membership == null ? "" : " expires " + membership.expiresAt),
                null);
        return true;
    }

    /** HMAC-SHA256 of the raw body with the webhook signing secret, constant-time. */
    private boolean bmacSignatureValid(String rawBody, String signature) {
        if (rawBody == null || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    bmac.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] provided = java.util.HexFormat.of().parseHex(signature.trim());
            return MessageDigest.isEqual(expected, provided);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Closes PENDING orders whose payment window lapsed. Runs on a schedule; a
     * late-arriving callback still flips a CLOSED order to PAID because the
     * money moved — {@link #handleNotify} only short-circuits on PAID.
     */
    @Scheduled(fixedDelayString = "${store.pay.order-close-interval-ms:60000}",
            initialDelayString = "${store.pay.order-close-initial-delay-ms:120000}")
    @Transactional
    public void closeExpiredOrders() {
        Instant now = Instant.now();
        for (MembershipOrder order : orders.findPendingExpired(now)) {
            order.status = MembershipOrderStatus.CLOSED;
            order.closedAt = now;
            orders.save(order);
            log.info("Closed expired membership order {}", order.orderNo);
        }
    }

    // ---- admin surface ----

    /** Every plan, active and inactive — the admin console. */
    public List<MembershipDtos.AdminMembershipPlanDto> adminPlans() {
        return plans.findAll().stream().map(MembershipService::adminPlanDto).toList();
    }

    @Transactional
    public MembershipDtos.AdminMembershipPlanDto createPlan(UUID adminId,
            MembershipDtos.AdminPlanRequest request) {
        requirePlanTerms(request, true);
        Instant now = Instant.now();
        MembershipPlan plan = new MembershipPlan(UuidV7.generate(), request.beeLevel(),
                request.durationDays(), request.priceFen(),
                request.active() == null ? true : request.active(),
                request.sort() == null ? request.beeLevel() : request.sort(),
                normalizeExternalUrl(request.externalUrl()), now, now);
        plans.save(plan);
        audit.record("USER", adminId.toString(), "membership.planCreated", "MEMBERSHIP_PLAN",
                plan.id.toString(), null, adminPlanDto(plan).toString(), null);
        return adminPlanDto(plan);
    }

    /** Partial update: omitted fields keep their current values. */
    @Transactional
    public MembershipDtos.AdminMembershipPlanDto updatePlan(UUID adminId, UUID planId,
            MembershipDtos.AdminPlanRequest request) {
        if (request == null) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED, "Request body required");
        }
        MembershipPlan plan = plans.findById(planId).orElseThrow(
                () -> new DomainException(StoreErrorCode.MEMBERSHIP_PLAN_NOT_FOUND,
                        "No membership plan: " + planId));
        MembershipPlan updated = new MembershipPlan(plan.id,
                request.beeLevel() == null ? plan.beeLevel : request.beeLevel(),
                request.durationDays() == null ? plan.durationDays : request.durationDays(),
                request.priceFen() == null ? plan.priceFen : request.priceFen(),
                request.active() == null ? plan.active : request.active(),
                request.sort() == null ? plan.sort : request.sort(),
                request.externalUrl() == null ? plan.externalUrl
                        : normalizeExternalUrl(request.externalUrl()),
                plan.createdAt, Instant.now());
        requirePlanTerms(new MembershipDtos.AdminPlanRequest(updated.beeLevel,
                updated.durationDays, updated.priceFen, updated.active, updated.sort,
                updated.externalUrl), false);
        plans.save(updated);
        audit.record("USER", adminId.toString(), "membership.planUpdated", "MEMBERSHIP_PLAN",
                plan.id.toString(), adminPlanDto(plan).toString(),
                adminPlanDto(updated).toString(), null);
        return adminPlanDto(updated);
    }

    @Transactional
    public void deletePlan(UUID adminId, UUID planId) {
        MembershipPlan plan = plans.findById(planId).orElseThrow(
                () -> new DomainException(StoreErrorCode.MEMBERSHIP_PLAN_NOT_FOUND,
                        "No membership plan: " + planId));
        plans.delete(planId);
        audit.record("USER", adminId.toString(), "membership.planDeleted", "MEMBERSHIP_PLAN",
                planId.toString(), adminPlanDto(plan).toString(), null, null);
    }

    /** Recent orders with buyer info — the admin console. */
    public List<MembershipDtos.AdminMembershipOrderDto> adminOrders() {
        return orders.findRecent(200).stream().map(order -> {
            StoreUser buyer = users.findById(order.userId).orElse(null);
            return new MembershipDtos.AdminMembershipOrderDto(
                    order.orderNo,
                    order.userId.toString(),
                    buyer == null ? null : buyer.email,
                    buyer == null ? null : buyer.displayName,
                    order.targetLevel,
                    order.durationDays,
                    order.priceFen,
                    order.status.name(),
                    order.channel,
                    order.gatewayTradeNo,
                    order.createdAt.toString(),
                    order.paidAt == null ? null : order.paidAt.toString(),
                    order.expiresAt.toString());
        }).toList();
    }

    // ---- helpers ----

    /**
     * Upgrades or extends the buyer's membership row. Never lowers anything: a
     * higher target replaces the level with a fresh window; an equal target (or
     * a race-lost lower target) extends whatever is held, so the paid duration
     * is never silently discarded.
     */
    private void applyMembership(MembershipOrder order, Instant now) {
        UserMembership membership = memberships.findByUserId(order.userId).orElse(null);
        StoreUser user = users.findById(order.userId).orElse(null);
        int base = user == null ? 0 : user.beeLevel;
        if (membership != null && membership.active(now)) {
            if (order.targetLevel > membership.level) {
                membership.level = order.targetLevel;
                membership.expiresAt = now.plus(Duration.ofDays(order.durationDays));
            } else {
                // Renewal, or a target the buyer outgrew between order and
                // callback: extend the held tier by the paid duration.
                membership.expiresAt = membership.expiresAt
                        .plus(Duration.ofDays(order.durationDays));
            }
            membership.updatedAt = now;
            memberships.save(membership);
            return;
        }
        if (order.targetLevel > base) {
            memberships.save(new UserMembership(order.userId, order.targetLevel,
                    now.plus(Duration.ofDays(order.durationDays)), now));
        } else {
            // Rare race: an admin granted the level permanently after checkout.
            audit.record("SYSTEM", "membership", "membership.appliedBelowBase",
                    "MEMBERSHIP_ORDER", order.orderNo, null,
                    "target L" + order.targetLevel + " <= base L" + base, null);
        }
    }

    private void closeIfLapsed(MembershipOrder order, Instant now) {
        if (order.status == MembershipOrderStatus.PENDING && order.expiresAt.isBefore(now)) {
            order.status = MembershipOrderStatus.CLOSED;
            order.closedAt = now;
            orders.save(order);
        }
    }

    private static void requirePlanTerms(MembershipDtos.AdminPlanRequest request,
            boolean forCreate) {
        if (request == null || (forCreate && (request.beeLevel() == null
                || request.durationDays() == null || request.priceFen() == null))) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "beeLevel, durationDays and priceFen are required");
        }
        if (request.beeLevel() != null && (request.beeLevel() < BeeLevel.WORKER.level
                || request.beeLevel() > BeeLevel.MAX_LEVEL)) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "beeLevel must be 1 (WORKER) through " + BeeLevel.MAX_LEVEL
                            + " (QUEEN) — LARVA is not purchasable");
        }
        if (request.durationDays() != null && request.durationDays() <= 0) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "durationDays must be positive");
        }
        if (request.priceFen() != null && request.priceFen() < 0) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "priceFen cannot be negative");
        }
        normalizeExternalUrl(request.externalUrl());
    }

    /** Blank → null; otherwise an absolute http(s) URL within 512 chars. */
    private static String normalizeExternalUrl(String externalUrl) {
        if (externalUrl == null || externalUrl.isBlank()) {
            return null;
        }
        String trimmed = externalUrl.trim();
        if (trimmed.length() > 512 || !(trimmed.startsWith("https://")
                || trimmed.startsWith("http://"))) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "externalUrl must be an absolute http(s) URL of at most 512 characters");
        }
        return trimmed;
    }

    /** MEM + yyMMddHHmmss + 12 random letters/digits — fits the gateway charset. */
    private static String newOrderNo() {
        StringBuilder suffix = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            suffix.append("ABCDEFGHJKLMNPQRSTUVWXYZ23456789".charAt(RANDOM.nextInt(32)));
        }
        return "MEM" + java.time.LocalDateTime.now()
                .truncatedTo(ChronoUnit.SECONDS)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + suffix;
    }

    /** Gateway-safe ASCII title (the cashier rejects emoji and percent signs). */
    private static String orderTitle(MembershipOrder order) {
        return "Infinia Level " + order.targetLevel + " (" + BeeLevel.of(order.targetLevel).name()
                + ") - " + order.durationDays + " days";
    }

    /** The ladder position the store enforces: max(base, active membership level). */
    public static int effectiveLevel(int baseBeeLevel, UserMembership membership, Instant now) {
        if (membership != null && membership.active(now) && membership.level > baseBeeLevel) {
            return membership.level;
        }
        return baseBeeLevel;
    }

    private static MembershipDtos.MembershipPlanDto planDto(MembershipPlan plan) {
        return new MembershipDtos.MembershipPlanDto(plan.id.toString(), plan.beeLevel,
                plan.durationDays, plan.priceFen, plan.active, plan.sort);
    }

    private static MembershipDtos.AdminMembershipPlanDto adminPlanDto(MembershipPlan plan) {
        return new MembershipDtos.AdminMembershipPlanDto(plan.id.toString(), plan.beeLevel,
                plan.durationDays, plan.priceFen, plan.active, plan.sort, plan.externalUrl,
                plan.createdAt.toString(), plan.updatedAt.toString());
    }

    private static MembershipDtos.MembershipOrderDto orderDto(MembershipOrder order) {
        return new MembershipDtos.MembershipOrderDto(
                order.orderNo,
                order.targetLevel,
                order.durationDays,
                order.priceFen,
                order.status.name(),
                order.channel,
                order.status == MembershipOrderStatus.PENDING ? order.payUrl : null,
                order.createdAt.toString(),
                order.paidAt == null ? null : order.paidAt.toString(),
                order.expiresAt.toString());
    }
}
