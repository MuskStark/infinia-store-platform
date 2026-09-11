package dev.infinia.store.app.web;

import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.MembershipService;
import dev.infinia.store.contract.api.MembershipDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Infinia Level purchase surface (会员等级购买): public pricing, the caller's
 * ladder position, order creation (redirect the buyer to the returned
 * {@code payUrl}) and order status polling for the return page.
 */
@RestController
@RequestMapping("/api/v1/membership")
class MembershipController {

    private final MembershipService membership;
    private final CurrentPrincipal principal;

    MembershipController(MembershipService membership, CurrentPrincipal principal) {
        this.membership = membership;
        this.principal = principal;
    }

    /** Public pricing — active plans only. */
    @GetMapping("/plans")
    public List<MembershipDtos.MembershipPlanDto> plans() {
        return membership.activePlans();
    }

    /** The caller's base/effective level, active membership and payable channels. */
    @GetMapping("/status")
    public MembershipDtos.MembershipStatusDto status() {
        return membership.status(principal.requireUserId());
    }

    @PostMapping("/orders")
    public MembershipDtos.MembershipOrderDto createOrder(
            @RequestBody MembershipDtos.CreateMembershipOrderRequest request) {
        return membership.createOrder(principal.requireUserId(), request);
    }

    @GetMapping("/orders/{orderNo}")
    public MembershipDtos.MembershipOrderDto order(@PathVariable String orderNo) {
        return membership.getOrder(principal.requireUserId(), orderNo);
    }
}
