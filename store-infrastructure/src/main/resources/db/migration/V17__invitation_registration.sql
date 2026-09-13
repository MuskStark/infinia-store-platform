-- Invitation-only registration (邀请注册): a store-wide operator switch plus
-- shareable invitation codes. While the switch is on, /auth/register refuses
-- self-service sign-ups unless a valid unused code is presented; codes are
-- issued by members at Infinia Level 2+ under a monthly quota (L2: 2, L3: 5,
-- L4: 10) and unlimited by platform admins. A code is single-use: the row is
-- claimed by the registrant's id inside the registration transaction, so two
-- concurrent sign-ups serialize on the pessimistic read.

-- Runtime-editable operator settings, keyed. The registration switch lives at
-- 'registration.invitation-only' and defaults to off (open registration).
CREATE TABLE system_setting (
    setting_key   VARCHAR(64) PRIMARY KEY,
    setting_value VARCHAR(1024) NOT NULL,
    updated_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

INSERT INTO system_setting (setting_key, setting_value, updated_at) VALUES
    ('registration.invitation-only', 'false', CURRENT_TIMESTAMP);

CREATE TABLE invitation_code (
    id         UUID PRIMARY KEY,
    code       VARCHAR(16) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    used_by    UUID,
    used_at    TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT ux_invitation_code UNIQUE (code),
    CONSTRAINT fk_invitation_code_creator FOREIGN KEY (created_by)
        REFERENCES store_user (id),
    CONSTRAINT fk_invitation_code_user FOREIGN KEY (used_by)
        REFERENCES store_user (id)
);

-- Monthly quota meter (created_by, created_at range) and the issuer's listing.
CREATE INDEX ix_invitation_code_creator ON invitation_code (created_by, created_at);
