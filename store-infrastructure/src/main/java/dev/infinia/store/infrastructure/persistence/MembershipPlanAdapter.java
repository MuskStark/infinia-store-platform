package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.MembershipPlan;
import dev.infinia.store.domain.port.BillingRepositories;
import dev.infinia.store.infrastructure.persistence.entity.MembershipPlanEntity;
import dev.infinia.store.infrastructure.persistence.repository.MembershipPlanJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class MembershipPlanAdapter implements BillingRepositories.MembershipPlanRepository {

    private final MembershipPlanJpaRepository jpa;

    public MembershipPlanAdapter(MembershipPlanJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<MembershipPlan> findAll() {
        return jpa.findAllByOrderBySortAscBeeLevelAscDurationDaysAsc().stream()
                .map(MembershipPlanAdapter::toDomain).toList();
    }

    @Override
    public Optional<MembershipPlan> findById(UUID id) {
        return jpa.findById(id).map(MembershipPlanAdapter::toDomain);
    }

    @Override
    public void save(MembershipPlan plan) {
        MembershipPlanEntity e = jpa.findById(plan.id).orElseGet(MembershipPlanEntity::new);
        e.id = plan.id;
        e.beeLevel = plan.beeLevel;
        e.durationDays = plan.durationDays;
        e.priceFen = plan.priceFen;
        e.active = plan.active;
        e.sort = plan.sort;
        e.createdAt = plan.createdAt;
        e.updatedAt = plan.updatedAt;
        jpa.save(e);
    }

    @Override
    public void delete(UUID id) {
        jpa.deleteById(id);
    }

    static MembershipPlan toDomain(MembershipPlanEntity e) {
        return new MembershipPlan(e.id, e.beeLevel, e.durationDays, e.priceFen, e.active,
                e.sort, e.createdAt, e.updatedAt);
    }
}
