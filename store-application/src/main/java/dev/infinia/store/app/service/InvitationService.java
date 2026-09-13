package dev.infinia.store.app.service;

import dev.infinia.store.contract.api.InvitationDtos;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.contract.type.BeeLevel;
import dev.infinia.store.contract.type.UserRole;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.InvitationCode;
import dev.infinia.store.domain.model.StoreUser;
import dev.infinia.store.domain.model.UserMembership;
import dev.infinia.store.domain.port.BillingRepositories;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.port.InvitationRepositories;
import dev.infinia.store.domain.service.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Invitation-only registration (邀请注册): the store-wide operator switch and
 * the shareable single-use invitation codes behind it.
 *
 * <p>Eligibility and monthly quotas follow the effective Infinia Level
 * ({@code max(base, active membership)}): Level 2 shares 2 codes per calendar
 * month, Level 3 five, Level 4 ten; below Level 2 nobody shares. Platform
 * admins issue without limit. A code is redeemed exactly once — the claim runs
 * under a pessimistic row lock inside the registration transaction.</p>
 */
@Service
public class InvitationService {

    /** system_setting key holding the invitation-only registration switch. */
    public static final String SETTING_INVITATION_ONLY = "registration.invitation-only";

    /** Level 2 (FORAGER) is where invitation sharing starts. */
    public static final int SHARE_MIN_LEVEL = BeeLevel.FORAGER.level;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 12;

    private final InvitationRepositories.InvitationCodeRepository codes;
    private final InvitationRepositories.SystemSettingRepository settings;
    private final IdentityRepositories.UserRepository users;
    private final BillingRepositories.UserMembershipRepository memberships;
    private final AuditService audit;

    public InvitationService(InvitationRepositories.InvitationCodeRepository codes,
            InvitationRepositories.SystemSettingRepository settings,
            IdentityRepositories.UserRepository users,
            BillingRepositories.UserMembershipRepository memberships,
            AuditService audit) {
        this.codes = codes;
        this.settings = settings;
        this.users = users;
        this.memberships = memberships;
        this.audit = audit;
    }

    // ---- registration policy ----

    /** True while the switch is on: registration requires an unused invitation code. */
    public boolean invitationRequired() {
        return settings.find(SETTING_INVITATION_ONLY)
                .map(Boolean::parseBoolean).orElse(false);
    }

    public InvitationDtos.RegistrationPolicyDto policy() {
        return new InvitationDtos.RegistrationPolicyDto(invitationRequired());
    }

    @Transactional
    public InvitationDtos.RegistrationPolicyDto setInvitationRequired(UUID adminId,
            boolean required) {
        boolean before = invitationRequired();
        settings.save(SETTING_INVITATION_ONLY, Boolean.toString(required));
        audit.record("USER", adminId.toString(), "registration.invitationOnly",
                "SYSTEM", SETTING_INVITATION_ONLY, Boolean.toString(before),
                Boolean.toString(required), null);
        return policy();
    }

    // ---- issuing ----

    /**
     * The member share path: requires effective Level 2+ and a free monthly
     * slot. Platform admins fall through to unlimited issuance.
     */
    @Transactional
    public InvitationDtos.InvitationCodeDto issue(UUID userId) {
        StoreUser user = userOrThrow(userId);
        if (user.roles.contains(UserRole.PLATFORM_ADMIN)) {
            return recordNewCode(user, "invitation.adminIssued");
        }
        int level = effectiveLevel(user);
        if (level < SHARE_MIN_LEVEL) {
            throw new DomainException(StoreErrorCode.INVITATION_LEVEL_REQUIRED,
                    "Infinia Level " + SHARE_MIN_LEVEL + " ("
                            + BeeLevel.of(SHARE_MIN_LEVEL).name() + ") or higher is "
                            + "required to share invitation codes",
                    Map.of("requiredBeeLevel", SHARE_MIN_LEVEL,
                            "currentBeeLevel", Math.max(level, 0)));
        }
        int quota = monthlyQuota(level);
        long issued = codes.countByCreatedBySince(userId, monthStart());
        if (issued >= quota) {
            throw new DomainException(StoreErrorCode.INVITATION_QUOTA_EXCEEDED,
                    "Monthly invitation quota exhausted (" + issued + " of " + quota
                            + " codes shared this month)",
                    Map.of("monthlyLimit", quota,
                            "issuedThisMonth", (int) Math.min(issued, Integer.MAX_VALUE)));
        }
        return recordNewCode(user, "invitation.issued");
    }

    /** The admin console path: no level gate, no monthly limit. */
    @Transactional
    public InvitationDtos.InvitationCodeDto adminIssue(UUID adminId) {
        StoreUser admin = userOrThrow(adminId);
        return recordNewCode(admin, "invitation.adminIssued");
    }

    /** The caller's share surface: quota, usage and every code they issued. */
    public InvitationDtos.MyInvitationsDto mine(UUID userId) {
        StoreUser user = userOrThrow(userId);
        boolean unlimited = user.roles.contains(UserRole.PLATFORM_ADMIN);
        int quota = unlimited ? 0 : monthlyQuota(effectiveLevel(user));
        long issued = codes.countByCreatedBySince(userId, monthStart());
        List<InvitationDtos.InvitationCodeDto> list = codes.findByCreatedBy(userId).stream()
                .map(code -> toDto(code, null)).toList();
        return new InvitationDtos.MyInvitationsDto(unlimited, quota, (int) issued, list);
    }

    /** Recent codes across all issuers, with account emails — the admin console. */
    public List<InvitationDtos.InvitationCodeDto> adminCodes() {
        return codes.findRecent(200).stream().map(code -> {
            StoreUser redeemer = code.usedBy == null ? null
                    : users.findById(code.usedBy).orElse(null);
            return toDto(code, redeemer == null ? null : redeemer.email);
        }).toList();
    }

    // ---- registration consumption ----

    /**
     * Normalizes a presented code (case-insensitive) and claims it for the new
     * account: must exist and be unused. Runs inside the caller's registration
     * transaction; {@code findByCodeForUpdate} serializes concurrent claims.
     */
    public void claimForRegistration(String rawCode, UUID newUserId) {
        String normalized = normalizeCode(rawCode);
        InvitationCode code = codes.findByCodeForUpdate(normalized).orElseThrow(
                () -> new DomainException(StoreErrorCode.INVITATION_INVALID,
                        "This invitation code does not exist"));
        if (code.used()) {
            throw new DomainException(StoreErrorCode.INVITATION_INVALID,
                    "This invitation code has already been used");
        }
        code.usedBy = newUserId;
        code.usedAt = Instant.now();
        codes.save(code);
        audit.record("USER", newUserId.toString(), "invitation.redeemed",
                "INVITATION_CODE", code.code, null,
                "creator " + code.createdBy, null);
    }

    // ---- helpers ----

    private InvitationDtos.InvitationCodeDto recordNewCode(StoreUser issuer, String action) {
        InvitationCode code = new InvitationCode(UuidV7.generate(), newCode(),
                issuer.id, Instant.now());
        codes.save(code);
        audit.record("USER", issuer.id.toString(), action, "INVITATION_CODE", code.code,
                null, "unused", null);
        return toDto(code, null);
    }

    private StoreUser userOrThrow(UUID userId) {
        return users.findById(userId).orElseThrow(
                () -> new DomainException(StoreErrorCode.NOT_FOUND, "User not found"));
    }

    private int effectiveLevel(StoreUser user) {
        UserMembership membership = memberships.findByUserId(user.id).orElse(null);
        return MembershipService.effectiveLevel(user.beeLevel, membership, Instant.now());
    }

    /** Codes are upper-case and unambiguous: 12 chars, no 0/O/1/I. */
    private static String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** Trim + upper-case; blank stays blank so callers decide requiredness. */
    private static String normalizeCode(String rawCode) {
        return rawCode == null ? "" : rawCode.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Start of the current calendar month in the server's zone. */
    private static Instant monthStart() {
        return YearMonth.now(ZoneId.systemDefault()).atDay(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    /**
     * Monthly share quota by effective level: L2 (FORAGER) → 2, L3 (GUARD) → 5,
     * L4 (QUEEN) → 10, and 0 below L2 (issuing is refused earlier with the
     * dedicated level error).
     */
    public static int monthlyQuota(int effectiveLevel) {
        return switch (Math.min(effectiveLevel, BeeLevel.MAX_LEVEL)) {
            case 2 -> 2;
            case 3 -> 5;
            case 4 -> 10;
            default -> 0;
        };
    }

    private InvitationDtos.InvitationCodeDto toDto(InvitationCode code, String usedByEmail) {
        String createdByEmail = users.findById(code.createdBy).map(u -> u.email).orElse(null);
        return new InvitationDtos.InvitationCodeDto(
                code.id.toString(),
                code.code,
                code.createdBy.toString(),
                createdByEmail,
                code.createdAt.toString(),
                code.usedBy == null ? null : code.usedBy.toString(),
                usedByEmail,
                code.usedAt == null ? null : code.usedAt.toString());
    }
}
