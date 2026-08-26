package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.OrgMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgMemberJpaRepository extends JpaRepository<OrgMemberEntity, OrgMemberEntity.MemberId> {

    List<OrgMemberEntity> findByOrganizationId(UUID organizationId);

    List<OrgMemberEntity> findByUserId(UUID userId);

    Optional<OrgMemberEntity> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);
}
