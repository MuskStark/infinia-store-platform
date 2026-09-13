package dev.infinia.store.contract.api;

import java.util.List;

/** DTOs for invitation-only registration (邀请注册) and shareable codes. */
public final class InvitationDtos {

    private InvitationDtos() {}

    /** Public registration policy — the sign-up form asks for a code when on. */
    public record RegistrationPolicyDto(boolean invitationRequired) {}

    /** Toggle payload for the admin console. */
    public record UpdateRegistrationPolicyRequest(Boolean invitationRequired) {}

    /** One invitation code; the used* fields are null until redeemed. */
    public record InvitationCodeDto(
            String codeId,
            String code,
            String createdBy,
            String createdByEmail,
            String createdAt,
            String usedBy,
            String usedByEmail,
            String usedAt) {
    }

    /** The caller's share surface: monthly quota, usage and issued codes. */
    public record MyInvitationsDto(
            boolean unlimited,
            int monthlyLimit,
            int issuedThisMonth,
            List<InvitationCodeDto> invitations) {
    }
}
