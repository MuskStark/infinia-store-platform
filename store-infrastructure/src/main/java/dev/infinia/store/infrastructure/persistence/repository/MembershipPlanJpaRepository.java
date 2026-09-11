package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.MembershipPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MembershipPlanJpaRepository extends JpaRepository<MembershipPlanEntity, UUID> {

    List<MembershipPlanEntity> findAllByOrderBySortAscBeeLevelAscDurationDaysAsc();
}
