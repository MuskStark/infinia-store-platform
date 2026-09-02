package dev.infinia.store.app.web;

import dev.infinia.store.app.service.AccountService;
import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.LibraryService;
import dev.infinia.store.contract.api.AccountDtos;
import dev.infinia.store.domain.model.StoreUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Registration and direct login (design §7.4). */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AccountService accounts;
    private final dev.infinia.store.app.security.LocalTokenService tokens;
    private final dev.infinia.store.app.service.AuditService audit;

    AuthController(AccountService accounts,
            dev.infinia.store.app.security.LocalTokenService tokens,
            dev.infinia.store.app.service.AuditService audit) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.audit = audit;
    }

    @PostMapping("/register")
    public ResponseEntity<AccountDtos.PublicUserDto> register(
            @RequestBody AccountDtos.RegisterRequest request) {
        StoreUser user = accounts.register(request.email(), request.password(),
                request.displayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(accounts.toDto(user));
    }

    /**
     * Direct email + password login for the store SPA: verifies the credential,
     * registers a revocable session and returns a signed access token
     * (same claims and key as the OAuth authorization server issues).
     */
    @PostMapping("/login")
    public AccountDtos.LoginResponse login(@RequestBody AccountDtos.LoginRequest request) {
        StoreUser user = accounts.authenticate(request.email(), request.password());
        String accessToken = tokens.mint(user, "store-web");
        audit.record("USER", user.id.toString(), "auth.login", "USER", user.id.toString(),
                null, "password", null);
        return new AccountDtos.LoginResponse(accessToken, accounts.toDto(user));
    }
}

/** Current user, sessions and devices (design §7.4). */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final AccountService accounts;
    private final CurrentPrincipal principal;

    MeController(AccountService accounts, CurrentPrincipal principal) {
        this.accounts = accounts;
        this.principal = principal;
    }

    @GetMapping
    public AccountDtos.PublicUserDto me() {
        UUID userId = principal.requireUserId();
        return accounts.toDto(accounts.userOrThrow(userId));
    }

    @PutMapping
    public AccountDtos.PublicUserDto update(@RequestBody AccountDtos.UpdateProfileRequest body) {
        UUID userId = principal.requireUserId();
        accounts.updateProfile(userId, body.displayName());
        return accounts.toDto(accounts.userOrThrow(userId));
    }

    /** Security page: change password after re-authentication (design §7.4 安全). */
    @PutMapping("/password")
    public AccountDtos.ChangePasswordResult changePassword(
            @RequestBody AccountDtos.ChangePasswordRequest body) {
        accounts.changePassword(principal.requireUserId(), body.currentPassword(),
                body.newPassword());
        return new AccountDtos.ChangePasswordResult(true, "Password updated");
    }

    @GetMapping("/sessions")
    public List<AccountDtos.SessionDto> sessions() {
        UUID userId = principal.requireUserId();
        return accounts.sessions(userId).stream()
                .map(s -> new AccountDtos.SessionDto(s.id().toString(), s.clientId(), s.kind(),
                        s.createdAt().toString(),
                        s.lastUsedAt() == null ? null : s.lastUsedAt().toString(),
                        s.remoteIpHash()))
                .toList();
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@PathVariable UUID sessionId) {
        accounts.revokeSession(principal.requireUserId(), sessionId);
    }

    @GetMapping("/devices")
    public List<AccountDtos.DeviceDto> devices() {
        UUID userId = principal.requireUserId();
        return accounts.devices(userId).stream()
                .map(d -> new AccountDtos.DeviceDto(d.id().toString(), d.publicId(), d.name(),
                        d.platform(), d.createdAt().toString(),
                        d.lastSeenAt() == null ? null : d.lastSeenAt().toString(), d.revoked()))
                .toList();
    }

    @DeleteMapping("/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeDevice(@PathVariable UUID deviceId) {
        accounts.revokeDevice(principal.requireUserId(), deviceId);
    }
}

/** Favorites and library (design §7.4). */
@RestController
@RequestMapping("/api/v1/me")
class LibraryController {

    private final LibraryService library;
    private final AccountService accounts;
    private final CurrentPrincipal principal;
    private final dev.infinia.store.app.service.BeeLevelService beeLevels;
    private final dev.infinia.store.domain.port.ListingRepository listings;
    private final dev.infinia.store.domain.port.ReleaseRepository releases;
    private final dev.infinia.store.domain.port.LibraryRepositories.FavoriteRepository favorites;
    private final dev.infinia.store.domain.port.LibraryRepositories.EntitlementRepository
            entitlements;

    LibraryController(LibraryService library, AccountService accounts,
            CurrentPrincipal principal,
            dev.infinia.store.app.service.BeeLevelService beeLevels,
            dev.infinia.store.domain.port.ListingRepository listings,
            dev.infinia.store.domain.port.ReleaseRepository releases,
            dev.infinia.store.domain.port.LibraryRepositories.FavoriteRepository favorites,
            dev.infinia.store.domain.port.LibraryRepositories.EntitlementRepository entitlements) {
        this.library = library;
        this.accounts = accounts;
        this.principal = principal;
        this.beeLevels = beeLevels;
        this.listings = listings;
        this.releases = releases;
        this.favorites = favorites;
        this.entitlements = entitlements;
    }

    @GetMapping("/library")
    public AccountDtos.LibraryDto library() {
        UUID userId = principal.requireUserId();
        var marks = library.favorites(userId);
        var listingIds = marks.stream().map(m -> m.listingId()).toList();
        var listingById = new java.util.HashMap<UUID, dev.infinia.store.domain.model.Listing>();
        for (var listing : listings.findByIds(listingIds)) {
            listingById.put(listing.id, listing);
        }
        List<AccountDtos.FavoriteDto> favoriteDtos = marks.stream()
                .map(m -> {
                    var listing = listingById.get(m.listingId());
                    var latest = listing == null ? null
                            : releases.findLatestVisible(listing.id, listing.defaultChannel)
                                    .orElse(null);
                    return new AccountDtos.FavoriteDto(
                            listing == null ? m.listingId().toString()
                                    : listing.coordinate().toString(),
                            listing == null ? null : listing.name("en"),
                            listing == null ? null : listing.type.name(),
                            latest == null ? null : latest.version.toString(),
                            m.addedAt().toString());
                })
                .toList();
        List<AccountDtos.EntitlementDto> entitlementDtos = library.entitlements(userId).stream()
                .map(e -> new AccountDtos.EntitlementDto(e.listingId().toString(), e.free(),
                        e.acquiredAt().toString()))
                .toList();
        List<AccountDtos.InstallEventDto> history = library.installHistory(userId).stream()
                .map(e -> new AccountDtos.InstallEventDto(e.idempotencyKey(), e.coordinate(),
                        e.version(), e.action(), e.outcome(),
                        e.occurredAt() == null ? null : e.occurredAt().toString()))
                .toList();
        return new AccountDtos.LibraryDto(favoriteDtos, entitlementDtos, history);
    }

    @PutMapping("/favorites/{listingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addFavorite(@PathVariable UUID listingId) {
        // Favoriting a Infinia Level gated listing requires meeting the gate (Infinia Level).
        listings.findById(listingId).ifPresent(beeLevels::requireListingAccess);
        library.addFavorite(principal.requireUserId(), listingId);
    }

    @DeleteMapping("/favorites/{listingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFavorite(@PathVariable UUID listingId) {
        library.removeFavorite(principal.requireUserId(), listingId);
    }

    /** Installed listings derived from telemetry (design §12.4 我的库: 已安装). */
    @GetMapping("/installed")
    public List<AccountDtos.InstalledItemDto> installed() {
        return library.installed(principal.requireUserId());
    }

    /** Installed listings with a newer published release (design §12.4 我的库: 可更新). */
    @GetMapping("/updates")
    public List<AccountDtos.InstalledItemDto> updates() {
        return library.installed(principal.requireUserId()).stream()
                .filter(AccountDtos.InstalledItemDto::updateAvailable)
                .toList();
    }
}

/** Optional install telemetry — batched and idempotent (design §10.2, ADR-009). */
@RestController
@RequestMapping("/api/v1")
class InstallEventsController {

    private final LibraryService library;
    private final CurrentPrincipal principal;

    InstallEventsController(LibraryService library, CurrentPrincipal principal) {
        this.library = library;
        this.principal = principal;
    }

    @PostMapping("/install-events")
    public ResponseEntity<Integer> report(@RequestBody List<AccountDtos.InstallEventRequest> events) {
        var current = principal.current();
        UUID userId = current == null || current.userId() == null ? null : current.userId();
        int accepted = 0;
        for (AccountDtos.InstallEventRequest event : events) {
            if (library.reportInstallEvent(userId, event.idempotencyKey(), event.coordinate(),
                    event.version(), event.type(), event.action(), event.outcome(),
                    event.hostVersion(), event.os(), event.arch(), event.occurredAt())) {
                accepted++;
            }
        }
        return ResponseEntity.accepted().body(accepted);
    }
}
