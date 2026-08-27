package dev.infinia.store.domain.port;

import dev.infinia.store.contract.type.UserRole;
import dev.infinia.store.domain.model.Credential;
import dev.infinia.store.domain.model.Device;
import dev.infinia.store.domain.model.Namespace;
import dev.infinia.store.domain.model.Organization;
import dev.infinia.store.domain.model.StoreUser;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Identity ports: users, credentials, devices, organizations and namespaces
 * (design §7.1). Implementations live in store-infrastructure.
 */
public final class IdentityRepositories {

    private IdentityRepositories() {}

    public interface UserRepository {
        Optional<StoreUser> findById(UUID id);

        Optional<StoreUser> findByEmailNormalized(String emailNormalized);

        boolean existsByEmailNormalized(String emailNormalized);

        void save(StoreUser user);
    }

    public interface CredentialRepository {
        void save(Credential credential);

        Optional<Credential> findByUserIdAndType(UUID userId, Credential.CredentialType type);
    }

    public interface DeviceRepository {
        List<Device> findByUserId(UUID userId);

        Optional<Device> findById(UUID id);

        void save(Device device);
    }

    public interface OrganizationRepository {
        Optional<Organization> findById(UUID id);

        Optional<Organization> findBySlug(String slug);

        boolean existsBySlug(String slug);

        void save(Organization organization);

        List<Organization> findByMember(UUID userId);

        void addMember(Organization.Member member);

        void removeMember(UUID organizationId, UUID userId);

        /** Returns the member's role in the organization, if a member. */
        Optional<UserRole> findMemberRole(UUID organizationId, UUID userId);

        void updateMemberRole(UUID organizationId, UUID userId, UserRole role);

        List<Organization.Member> findMembers(UUID organizationId);

        boolean isMember(UUID organizationId, UUID userId);

        boolean hasRole(UUID organizationId, UUID userId, UserRole role);
    }

    public interface NamespaceRepository {
        Optional<Namespace> findByName(String name);

        boolean existsByName(String name);

        void save(Namespace namespace);

        List<Namespace> findOwnedBy(UUID userId, UUID organizationId);
    }

    /** Session ledger for active grants (design §7.4). */
    public record UserSessionRecord(UUID id, UUID userId, String clientId, String kind,
            UUID deviceId, Instant createdAt, Instant lastUsedAt, boolean revoked,
            String remoteIpHash) {
    }

    public interface SessionRepository {
        void save(UserSessionRecord session);

        List<UserSessionRecord> findByUserId(UUID userId);

        Optional<UserSessionRecord> findById(UUID id);

        void markRevoked(UUID id);
    }

    /** True when the subject owns the namespace directly or through an organization role. */
    public static boolean ownsNamespace(NamespaceRepository namespaces, OrganizationRepository orgs,
            Namespace namespace, UUID userId) {
        if (namespace == null) {
            return false;
        }
        if (namespace.organizationId() != null) {
            return orgs.hasRole(namespace.organizationId(), userId, UserRole.ORG_ADMIN)
                    || orgs.isMember(namespace.organizationId(), userId);
        }
        return userId != null && userId.equals(namespace.ownerUserId());
    }
}
