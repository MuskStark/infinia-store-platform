package dev.infinia.store.app.service;

import dev.infinia.store.domain.DomainException;
import dev.infinia.store.contract.error.StoreErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Extracts the store principal from the current JWT (design §7.3). */
@Service
public class CurrentPrincipal {

    public record Principal(UUID userId, String email, List<String> roles, String clientId,
            UUID sessionId, boolean servicePrincipal) {

        public boolean hasRole(String role) {
            return roles != null && roles.contains(role);
        }

        public boolean hasAnyRole(String... wanted) {
            for (String role : wanted) {
                if (hasRole(role)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Returns the principal or null for anonymous requests. */
    public Principal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        String uid = jwt.getClaimAsString("uid");
        List<String> roles = jwt.getClaimAsStringList("roles");
        boolean service = uid == null;
        String sid = jwt.getClaimAsString("sid");
        return new Principal(uid == null ? null : UUID.fromString(uid),
                jwt.getClaimAsString("email"), roles == null ? List.of() : roles,
                jwt.getClaimAsString("azp") != null ? jwt.getClaimAsString("azp")
                        : jwt.getClaimAsString("client_id"),
                sid == null ? null : UUID.fromString(sid),
                service);
    }

    public Principal require() {
        Principal principal = current();
        if (principal == null) {
            throw new DomainException(StoreErrorCode.UNAUTHENTICATED,
                    "Authentication required for this operation");
        }
        return principal;
    }

    public UUID requireUserId() {
        Principal principal = require();
        if (principal.userId() == null) {
            throw new DomainException(StoreErrorCode.FORBIDDEN,
                    "A user token is required (service tokens cannot own personal resources)");
        }
        return principal.userId();
    }
}
