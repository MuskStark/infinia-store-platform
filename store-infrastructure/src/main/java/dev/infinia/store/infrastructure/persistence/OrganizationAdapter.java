package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.contract.type.UserRole;
import dev.infinia.store.domain.model.Organization;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.infrastructure.persistence.entity.OrgMemberEntity;
import dev.infinia.store.infrastructure.persistence.entity.OrganizationEntity;
import dev.infinia.store.infrastructure.persistence.repository.OrgMemberJpaRepository;
import dev.infinia.store.infrastructure.persistence.repository.OrganizationJpaRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class OrganizationAdapter implements IdentityRepositories.OrganizationRepository {

    private final OrganizationJpaRepository jpa;
    private final OrgMemberJpaRepository members;

    public OrganizationAdapter(OrganizationJpaRepository jpa, OrgMemberJpaRepository members) {
        this.jpa = jpa;
        this.members = members;
    }

    @Override
    public Optional<Organization> findById(UUID id) {
        return jpa.findById(id).map(OrganizationAdapter::toDomain);
    }

    @Override
    public Optional<Organization> findBySlug(String slug) {
        return jpa.findBySlug(slug).map(OrganizationAdapter::toDomain);
    }

    @Override
    public boolean existsBySlug(String slug) {
        return jpa.existsBySlug(slug);
    }

    @Override
    public void save(Organization organization) {
        OrganizationEntity e = jpa.findById(organization.id()).orElseGet(OrganizationEntity::new);
        e.id = organization.id();
        e.slug = organization.slug();
        e.name = organization.name();
        e.ownerUserId = organization.ownerUserId();
        e.createdAt = organization.createdAt();
        jpa.save(e);
    }

    @Override
    public List<Organization> findByMember(UUID userId) {
        List<UUID> orgIds = members.findByUserId(userId).stream()
                .map(m -> m.organizationId)
                .distinct()
                .toList();
        List<Organization> result = new ArrayList<>(jpa.findAllById(orgIds).stream()
                .map(OrganizationAdapter::toDomain).toList());
        jpa.findAll().stream()
                .filter(o -> userId.equals(o.ownerUserId))
                .map(OrganizationAdapter::toDomain)
                .forEach(o -> {
                    if (result.stream().noneMatch(existing -> existing.id().equals(o.id()))) {
                        result.add(o);
                    }
                });
        return result;
    }

    @Override
    public void addMember(Organization.Member member) {
        OrgMemberEntity e = new OrgMemberEntity();
        e.organizationId = member.organizationId();
        e.userId = member.userId();
        e.role = member.role().name();
        e.joinedAt = member.joinedAt();
        members.save(e);
    }

    @Override
    public List<Organization.Member> findMembers(UUID organizationId) {
        return members.findByOrganizationId(organizationId).stream()
                .map(m -> new Organization.Member(m.organizationId, m.userId,
                        UserRole.valueOf(m.role), m.joinedAt))
                .toList();
    }

    @Override
    public boolean isMember(UUID organizationId, UUID userId) {
        return members.findByOrganizationIdAndUserId(organizationId, userId).isPresent();
    }

    @Override
    public boolean hasRole(UUID organizationId, UUID userId, UserRole role) {
        return members.findByOrganizationIdAndUserId(organizationId, userId)
                .map(m -> UserRole.valueOf(m.role) == role)
                .orElse(false);
    }

    private static Organization toDomain(OrganizationEntity e) {
        return new Organization(e.id, e.slug, e.name, e.ownerUserId, e.createdAt);
    }
}
