package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A single-use invitation code (邀请码) for invitation-only registration. Issued
 * by members at Infinia Level 2+ under a monthly quota, or unlimited by
 * platform admins; {@code usedBy}/{@code usedAt} stay null until the code is
 * claimed by a registration, and the claim happens under a row lock so two
 * concurrent sign-ups can never redeem the same code.
 */
public class InvitationCode {
    public UUID id;
    public String code;
    public UUID createdBy;
    public Instant createdAt;
    public UUID usedBy;
    public Instant usedAt;

    public InvitationCode() {
    }

    public InvitationCode(UUID id, String code, UUID createdBy, Instant createdAt) {
        this.id = id;
        this.code = code;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public boolean used() {
        return usedBy != null;
    }
}
