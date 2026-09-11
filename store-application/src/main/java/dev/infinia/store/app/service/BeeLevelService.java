package dev.infinia.store.app.service;

import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.contract.type.BeeLevel;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.StoreUser;
import dev.infinia.store.domain.port.BillingRepositories;
import dev.infinia.store.domain.port.IdentityRepositories;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Infinia Level (会员等级) identity and gating: resolves the current viewer's hive
 * level from their live account record (never the possibly-stale JWT) and
 * enforces {@code listing.minBeeLevel} on every view/download surface.
 *
 * <p>The ladder position is {@code max(base bee_level, active purchased
 * membership level)} — purchases (MembershipService) never lower an
 * admin-granted level and expire lazily from {@code user_membership.expires_at}.
 * Anonymous visitors rank below every positive gate; platform admins rank
 * above the whole ladder. A gate of 0 means public.</p>
 */
@Service
public class BeeLevelService {

    /** Viewer level for anonymous requests — passes only ungated listings. */
    public static final int ANONYMOUS = -1;

    private final CurrentPrincipal principal;
    private final IdentityRepositories.UserRepository users;
    private final BillingRepositories.UserMembershipRepository memberships;

    public BeeLevelService(CurrentPrincipal principal,
            IdentityRepositories.UserRepository users,
            BillingRepositories.UserMembershipRepository memberships) {
        this.principal = principal;
        this.users = users;
        this.memberships = memberships;
    }

    /**
     * The caller's effective bee level. PLATFORM_ADMIN bypasses the ladder;
     * service principals (no user id) count as anonymous.
     */
    public int viewerLevel() {
        CurrentPrincipal.Principal current = principal.current();
        if (current == null || current.userId() == null) {
            return ANONYMOUS;
        }
        if (current.hasRole("PLATFORM_ADMIN")) {
            return BeeLevel.MAX_LEVEL + 1;
        }
        return users.findById(current.userId())
                .map(u -> MembershipService.effectiveLevel(u.beeLevel,
                        memberships.findByUserId(u.id).orElse(null), Instant.now()))
                .orElse(ANONYMOUS);
    }

    /** The live account behind the current request, when a user token is present. */
    public StoreUser viewerUser() {
        CurrentPrincipal.Principal current = principal.current();
        if (current == null || current.userId() == null) {
            return null;
        }
        return users.findById(current.userId()).orElse(null);
    }

    /**
     * Throws {@code bee_level_required} unless the current viewer may see or
     * download the listing. Ungated listings always pass.
     */
    public void requireListingAccess(Listing listing) {
        if (listing == null || listing.minBeeLevel <= BeeLevel.LARVA.level) {
            return;
        }
        int level = viewerLevel();
        if (level >= listing.minBeeLevel) {
            return;
        }
        BeeLevel required = BeeLevel.of(listing.minBeeLevel);
        throw new DomainException(StoreErrorCode.BEE_LEVEL_REQUIRED,
                "Infinia Level " + required.name() + " (" + required.level + ") or higher is "
                        + "required for " + listing.coordinate(),
                Map.of("requiredBeeLevel", listing.minBeeLevel,
                        "requiredBeeLevelName", required.name(),
                        "currentBeeLevel", Math.max(level, 0)));
    }
}
