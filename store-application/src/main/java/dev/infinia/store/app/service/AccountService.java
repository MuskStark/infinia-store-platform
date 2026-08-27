package dev.infinia.store.app.service;

import dev.infinia.store.contract.api.AccountDtos;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.contract.type.UserRole;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Credential;
import dev.infinia.store.domain.model.StoreUser;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.port.PasswordHasher;
import dev.infinia.store.domain.service.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Registration and account management (design §7.4). */
@Service
public class AccountService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final IdentityRepositories.UserRepository users;
    private final IdentityRepositories.CredentialRepository credentials;
    private final IdentityRepositories.SessionRepository sessions;
    private final IdentityRepositories.DeviceRepository devices;
    private final PasswordHasher hasher;

    public AccountService(IdentityRepositories.UserRepository users,
            IdentityRepositories.CredentialRepository credentials,
            IdentityRepositories.SessionRepository sessions,
            IdentityRepositories.DeviceRepository devices, PasswordHasher hasher) {
        this.users = users;
        this.credentials = credentials;
        this.sessions = sessions;
        this.devices = devices;
        this.hasher = hasher;
    }

    @Transactional
    public StoreUser register(String email, String password, String displayName) {
        String normalized = normalizeEmail(email);
        if (!EMAIL.matcher(normalized).matches()) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED, "Invalid email address");
        }
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "Password must be 8-128 characters");
        }
        if (users.existsByEmailNormalized(normalized)) {
            throw new DomainException(StoreErrorCode.EMAIL_TAKEN,
                    "An account with this email already exists");
        }
        UUID id = UuidV7.generate();
        StoreUser user = new StoreUser(id, email, normalized,
                displayName == null || displayName.isBlank()
                        ? email.substring(0, email.indexOf('@')) : displayName.trim(),
                Set.of(UserRole.USER), "ACTIVE", Instant.now());
        users.save(user);
        credentials.save(new Credential(UuidV7.generate(), id, Credential.CredentialType.PASSWORD,
                hasher.hash(password), Instant.now()));
        return user;
    }

    public StoreUser userOrThrow(UUID userId) {
        return users.findById(userId).orElseThrow(
                () -> new DomainException(StoreErrorCode.NOT_FOUND, "User not found"));
    }

    /** Verifies email + password for the direct login endpoint (design §7.4). */
    public StoreUser authenticate(String email, String password) {
        String normalized = normalizeEmail(email);
        StoreUser user = users.findByEmailNormalized(normalized)
                .orElseThrow(() -> new DomainException(StoreErrorCode.INVALID_CREDENTIALS,
                        "Email or password is incorrect"));
        Credential credential = credentials
                .findByUserIdAndType(user.id, Credential.CredentialType.PASSWORD)
                .orElseThrow(() -> new DomainException(StoreErrorCode.INVALID_CREDENTIALS,
                        "Email or password is incorrect"));
        if (password == null || !hasher.matches(password, credential.secretHash())) {
            throw new DomainException(StoreErrorCode.INVALID_CREDENTIALS,
                    "Email or password is incorrect");
        }
        if (!"ACTIVE".equals(user.status)) {
            throw new DomainException(StoreErrorCode.FORBIDDEN,
                    "This account is not active");
        }
        return user;
    }

    public void updateProfile(UUID userId, String displayName) {
        if (displayName == null || displayName.isBlank() || displayName.length() > 64) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "Display name must be 1-64 characters");
        }
        StoreUser user = userOrThrow(userId);
        user.displayName = displayName.trim();
        users.save(user);
    }

    /**
     * Changes the password credential after re-authenticating with the current one
     * (design §7.4 安全). New passwords follow the same policy as registration.
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 128) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "New password must be 8-128 characters");
        }
        Credential existing = credentials
                .findByUserIdAndType(userId, Credential.CredentialType.PASSWORD)
                .orElseThrow(() -> new DomainException(StoreErrorCode.INVALID_CREDENTIALS,
                        "No password credential on this account"));
        if (currentPassword == null || !hasher.matches(currentPassword, existing.secretHash())) {
            throw new DomainException(StoreErrorCode.WRONG_PASSWORD,
                    "Current password is incorrect");
        }
        if (currentPassword.equals(newPassword)) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "New password must differ from the current one");
        }
        credentials.save(new Credential(existing.id(), userId, Credential.CredentialType.PASSWORD,
                hasher.hash(newPassword), existing.createdAt()));
    }

    public List<IdentityRepositories.UserSessionRecord> sessions(UUID userId) {
        return sessions.findByUserId(userId);
    }

    public void revokeSession(UUID userId, UUID sessionId) {
        IdentityRepositories.UserSessionRecord record = sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "Session not found"));
        if (!record.userId().equals(userId)) {
            throw DomainException.forbidden("Cannot revoke another user's session");
        }
        sessions.markRevoked(sessionId);
    }

    public List<dev.infinia.store.domain.model.Device> devices(UUID userId) {
        return devices.findByUserId(userId);
    }

    public void revokeDevice(UUID userId, UUID deviceId) {
        dev.infinia.store.domain.model.Device device = devices.findById(deviceId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "Device not found"));
        if (!device.userId().equals(userId)) {
            throw DomainException.forbidden("Cannot revoke another user's device");
        }
        devices.save(new dev.infinia.store.domain.model.Device(device.id(), device.userId(),
                device.publicId(), device.name(), device.platform(), device.createdAt(),
                device.lastSeenAt(), true));
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public AccountDtos.PublicUserDto toDto(StoreUser user) {
        return new AccountDtos.PublicUserDto(user.id.toString(), user.email, user.displayName,
                user.roles.stream().map(Enum::name).toList(), user.createdAt.toString());
    }
}
