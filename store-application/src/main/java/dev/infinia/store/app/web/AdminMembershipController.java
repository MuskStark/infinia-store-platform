package dev.infinia.store.app.web;

import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.MembershipService;
import dev.infinia.store.contract.api.MembershipDtos;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Membership plan and order console (管理 · 会员套餐): edit prices, durations and
 * availability at runtime, and watch the purchase order stream. Guarded by the
 * /api/v1/admin/** PLATFORM_ADMIN rule; every mutation is audited.
 */
@RestController
@RequestMapping("/api/v1/admin/membership")
class AdminMembershipController {

    private final MembershipService membership;
    private final CurrentPrincipal principal;

    AdminMembershipController(MembershipService membership, CurrentPrincipal principal) {
        this.membership = membership;
        this.principal = principal;
    }

    @GetMapping("/plans")
    public List<MembershipDtos.AdminMembershipPlanDto> plans() {
        principal.require();
        return membership.adminPlans();
    }

    @PostMapping("/plans")
    public MembershipDtos.AdminMembershipPlanDto create(
            @RequestBody MembershipDtos.AdminPlanRequest request) {
        return membership.createPlan(principal.requireUserId(), request);
    }

    /** Partial update: omitted fields keep their current values. */
    @PutMapping("/plans/{planId}")
    public MembershipDtos.AdminMembershipPlanDto update(@PathVariable UUID planId,
            @RequestBody MembershipDtos.AdminPlanRequest request) {
        return membership.updatePlan(principal.requireUserId(), planId, request);
    }

    @DeleteMapping("/plans/{planId}")
    public void delete(@PathVariable UUID planId) {
        membership.deletePlan(principal.requireUserId(), planId);
    }

    /** Recent purchase orders, newest first, with buyer info. */
    @GetMapping("/orders")
    public List<MembershipDtos.AdminMembershipOrderDto> orders() {
        principal.require();
        return membership.adminOrders();
    }
}
