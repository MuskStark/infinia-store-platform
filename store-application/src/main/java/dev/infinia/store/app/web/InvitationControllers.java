package dev.infinia.store.app.web;

import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.InvitationService;
import dev.infinia.store.contract.api.InvitationDtos;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Invitation-code sharing (我的 · 邀请码): members at effective Level 2+ mint
 * single-use registration codes under a monthly quota (L2: 2, L3: 5, L4: 10);
 * platform admins share without limit. Every issuance is audited.
 */
@RestController
@RequestMapping("/api/v1/invitations")
class InvitationController {

    private final InvitationService invitations;
    private final CurrentPrincipal principal;

    InvitationController(InvitationService invitations, CurrentPrincipal principal) {
        this.invitations = invitations;
        this.principal = principal;
    }

    /** Mints one code against the caller's monthly quota (admins: unlimited). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationDtos.InvitationCodeDto create() {
        return invitations.issue(principal.requireUserId());
    }

    /** The caller's quota, usage this month and every code they issued. */
    @GetMapping("/mine")
    public InvitationDtos.MyInvitationsDto mine() {
        return invitations.mine(principal.requireUserId());
    }
}

/**
 * Invitation console (管理 · 邀请码): the invitation-only registration switch,
 * unlimited code issuance and the store-wide code ledger. Guarded by the
 * /api/v1/admin/** PLATFORM_ADMIN rule; the toggle and every issuance are
 * audited.
 */
@RestController
@RequestMapping("/api/v1/admin/invitations")
class AdminInvitationController {

    private final InvitationService invitations;
    private final CurrentPrincipal principal;

    AdminInvitationController(InvitationService invitations, CurrentPrincipal principal) {
        this.invitations = invitations;
        this.principal = principal;
    }

    /** Recent codes across all issuers, newest first. */
    @GetMapping
    public List<InvitationDtos.InvitationCodeDto> codes() {
        principal.require();
        return invitations.adminCodes();
    }

    /** Issues a code with no level gate and no monthly limit. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationDtos.InvitationCodeDto create() {
        return invitations.adminIssue(principal.requireUserId());
    }

    /** Current value of the invitation-only registration switch. */
    @GetMapping("/settings")
    public InvitationDtos.RegistrationPolicyDto settings() {
        principal.require();
        return invitations.policy();
    }

    /** Flips the invitation-only registration switch. */
    @PutMapping("/settings")
    public InvitationDtos.RegistrationPolicyDto updateSettings(
            @RequestBody InvitationDtos.UpdateRegistrationPolicyRequest body) {
        if (body == null || body.invitationRequired() == null) {
            throw new dev.infinia.store.domain.DomainException(
                    dev.infinia.store.contract.error.StoreErrorCode.VALIDATION_FAILED,
                    "invitationRequired is required");
        }
        return invitations.setInvitationRequired(principal.requireUserId(),
                body.invitationRequired());
    }
}
