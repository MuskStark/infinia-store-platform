-- Membership purchase (会员等级购买): admin-managed plans, purchase orders and
-- per-user time-limited memberships. The permanent admin-granted ladder stays on
-- store_user.bee_level; the effective level is max(base, active membership), so
-- a purchased membership never lowers an admin-granted level and expiry is
-- computed lazily from user_membership.expires_at (no demotion job needed).

CREATE TABLE membership_plan (
    id            UUID PRIMARY KEY,
    bee_level     INT NOT NULL,
    duration_days INT NOT NULL,
    price_fen     BIGINT NOT NULL,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    sort          INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_membership_plan_level CHECK (bee_level BETWEEN 1 AND 4),
    CONSTRAINT ck_membership_plan_duration CHECK (duration_days > 0),
    CONSTRAINT ck_membership_plan_price CHECK (price_fen >= 0)
);

-- Sensible defaults so a fresh deployment has something to sell; the admin
-- console can edit, deactivate or delete every row (LARVA is not purchasable,
-- hence the CHECK above starts at WORKER).
INSERT INTO membership_plan (id, bee_level, duration_days, price_fen, active, sort,
        created_at, updated_at) VALUES
    ('5eed5eed-5eed-7000-8000-000000000001', 1, 30, 600, TRUE, 1,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('5eed5eed-5eed-7000-8000-000000000002', 2, 30, 1600, TRUE, 2,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('5eed5eed-5eed-7000-8000-000000000003', 3, 90, 3600, TRUE, 3,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('5eed5eed-5eed-7000-8000-000000000004', 4, 365, 6600, TRUE, 4,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

CREATE TABLE membership_order (
    id               UUID PRIMARY KEY,
    order_no         VARCHAR(64) NOT NULL,
    user_id          UUID NOT NULL,
    plan_id          UUID NOT NULL,
    target_level     INT NOT NULL,
    duration_days    INT NOT NULL,
    price_fen        BIGINT NOT NULL,
    status           VARCHAR(16) NOT NULL,
    channel          VARCHAR(16) NOT NULL,
    gateway_trade_no VARCHAR(128),
    pay_url          VARCHAR(1024),
    created_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    paid_at          TIMESTAMP(6) WITH TIME ZONE,
    closed_at        TIMESTAMP(6) WITH TIME ZONE,
    expires_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ux_membership_order_no UNIQUE (order_no),
    CONSTRAINT fk_membership_order_user FOREIGN KEY (user_id) REFERENCES store_user (id),
    CONSTRAINT fk_membership_order_plan FOREIGN KEY (plan_id) REFERENCES membership_plan (id)
);
CREATE INDEX ix_membership_order_user ON membership_order (user_id, created_at);
CREATE INDEX ix_membership_order_close ON membership_order (status, expires_at);

CREATE TABLE user_membership (
    user_id    UUID PRIMARY KEY,
    level      INT NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_user_membership_user FOREIGN KEY (user_id) REFERENCES store_user (id),
    CONSTRAINT ck_user_membership_level CHECK (level BETWEEN 1 AND 4)
);
