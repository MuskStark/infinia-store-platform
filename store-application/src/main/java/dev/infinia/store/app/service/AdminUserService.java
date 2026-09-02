package dev.infinia.store.app.service;

import dev.infinia.store.contract.api.AccountDtos;
import dev.infinia.store.contract.api.AccountDtos.UpdateAdminUserRequest;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.contract.type.BeeLevel;
import dev.infinia.store.contract.type.UserRole;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.StoreUser;
import dev.infinia.store.domain.port.IdentityRepositories;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Platform-admin user management (design §12.4 管理 · 用户管理): browse every
 * account, promote or demote Infinia Levels, disable/enable accounts and
 * adjust roles. Every mutation is audited; admins cannot lock themselves out.
 */
@Service
public class AdminUserService {

    private final IdentityRepositories.UserRepository users;
    private final IdentityRepositories.SessionRepository sessions;
    private final AuditService audit;

    public AdminUserService(IdentityRepositories.UserRepository users,
            IdentityRepositories.SessionRepository sessions, AuditService audit) {
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
    }

    public List<AccountDtos.AdminUserDto> listUsers() {
        return users.findAll().stream().map(AdminUserService::toDto).toList();
    }

    /**
     * Applies a partial update (bee level, status, roles, display name); omitted
     * fields keep their current values. Disabling an account also revokes its
     * sessions so outstanding tokens stop working immediately.
     */
    @Transactional
    public AccountDtos.AdminUserDto updateUser(UUID adminId, UUID userId,
            UpdateAdminUserRequest request) {
        if (request == null) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED, "Request body required");
        }
        StoreUser user = users.findById(userId).orElseThrow(
                () -> new DomainException(StoreErrorCode.NOT_FOUND, "User not found: " + userId));

        if (request.beeLevel() != null) {
            if (!BeeLevel.isValid(request.beeLevel())) {
                throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                        "Infinia Level (beeLevel) must be 0 (LARVA) through " + BeeLevel.MAX_LEVEL + " (QUEEN)");
            }
            if (request.beeLevel() != user.beeLevel) {
                audit.record("USER", adminId.toString(), "user.beeLevel", "USER", user.id.toString(),
                        "L" + user.beeLevel, "L" + request.beeLevel(), null);
                user.beeLevel = request.beeLevel();
            }
        }
        if (request.status() != null) {
            String status = request.status().trim().toUpperCase();
            if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
                throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                        "status must be ACTIVE or DISABLED");
            }
            if (adminId.equals(userId) && "DISABLED".equals(status)) {
                throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                        "Admins cannot disable their own account");
            }
            if (!status.equals(user.status)) {
                audit.record("USER", adminId.toString(), "user.status", "USER",
                        user.id.toString(), user.status, status, null);
                user.status = status;
                if ("DISABLED".equals(status)) {
                    // Kill live grants: the session ledger invalidates issued JWTs.
                    sessions.findByUserId(userId).forEach(s -> sessions.markRevoked(s.id()));
                }
            }
        }
        if (request.roles() != null) {
            Set<UserRole> roles = parseRoles(request.roles());
            if (adminId.equals(userId) && !roles.contains(UserRole.PLATFORM_ADMIN)) {
                throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                        "Admins cannot remove their own PLATFORM_ADMIN role");
            }
            if (!roles.equals(user.roles)) {
                audit.record("USER", adminId.toString(), "user.roles", "USER",
                        user.id.toString(), String.join(",", roleNames(user.roles)),
                        String.join(",", roleNames(roles)), null);
                user.roles = roles;
            }
        }
        if (request.displayName() != null) {
            if (request.displayName().isBlank() || request.displayName().length() > 64) {
                throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                        "Display name must be 1-64 characters");
            }
            user.displayName = request.displayName().trim();
        }
        user.lastLoginAt = user.lastLoginAt == null ? null : user.lastLoginAt;
        users.save(user);
        return toDto(user);
    }

    private static Set<UserRole> parseRoles(List<String> requested) {
        if (requested.isEmpty()) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "roles cannot be empty — every account keeps at least USER");
        }
        Set<UserRole> roles = new LinkedHashSet<>();
        for (String raw : requested) {
            UserRole role;
            try {
                role = UserRole.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                        "Unknown role: " + raw);
            }
            roles.add(role);
        }
        roles.add(UserRole.USER);
        return roles;
    }

    private static List<String> roleNames(Set<UserRole> roles) {
        return roles.stream().map(Enum::name).sorted().toList();
    }

    public static AccountDtos.AdminUserDto toDto(StoreUser user) {
        return new AccountDtos.AdminUserDto(
                user.id.toString(),
                user.email,
                user.displayName,
                user.roles.stream().map(Enum::name).sorted().toList(),
                user.status,
                user.beeLevel,
                user.mfaEnabled,
                user.createdAt.toString(),
                user.lastLoginAt == null ? null : user.lastLoginAt.toString());
    }
}
