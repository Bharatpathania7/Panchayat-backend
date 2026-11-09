-- Parivar (family): created with coins, joined by application or openly, roles with a permission matrix,
-- a shared treasury, activity from gifts, levels and weekly / total ranking. Everything configurable is in these tables
-- and edited from the admin panel (Parivar management).

CREATE TABLE parivar_settings (
    id                      INT PRIMARY KEY CHECK (id = 1),
    enabled                 BOOLEAN        NOT NULL DEFAULT FALSE,
    creation_open           BOOLEAN        NOT NULL DEFAULT TRUE,
    -- AUTO: coins = creation_inr x coins per INR. MANUAL: manual_coins exactly.
    creation_mode           VARCHAR(8)     NOT NULL DEFAULT 'AUTO' CHECK (creation_mode IN ('AUTO', 'MANUAL')),
    creation_inr            NUMERIC(10, 2) NOT NULL DEFAULT 499 CHECK (creation_inr >= 0),
    -- NULL: worked out from the cheapest active coin pack (its coins / its price in rupees)
    coins_per_inr           NUMERIC(14, 4) CHECK (coins_per_inr > 0),
    manual_coins            BIGINT         NOT NULL DEFAULT 0 CHECK (manual_coins >= 0),
    required_charm          BIGINT         NOT NULL DEFAULT 0 CHECK (required_charm >= 0),
    consume_charm           BOOLEAN        NOT NULL DEFAULT FALSE,
    one_per_user            BOOLEAN        NOT NULL DEFAULT TRUE,
    -- gifts between members: this many coins = 1 Parivar EXP
    gift_coins_per_exp      INT            NOT NULL DEFAULT 10 CHECK (gift_coins_per_exp >= 1),
    gift_counts_for_ranking BOOLEAN        NOT NULL DEFAULT TRUE,
    gift_user_daily_cap     BIGINT         NOT NULL DEFAULT 50000 CHECK (gift_user_daily_cap >= 0),
    -- donations: this many coins = 1 Parivar EXP (0 = donations give no EXP), minimum donation in coins
    donation_coins_per_exp  INT            NOT NULL DEFAULT 10 CHECK (donation_coins_per_exp >= 0),
    min_donation            BIGINT         NOT NULL DEFAULT 100 CHECK (min_donation >= 1),
    updated_by              VARCHAR(120),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now()
);
INSERT INTO parivar_settings (id) VALUES (1);

CREATE TABLE parivar_levels (
    level      INT PRIMARY KEY CHECK (level >= 1),
    exp_needed BIGINT NOT NULL CHECK (exp_needed >= 0),
    capacity   INT    NOT NULL CHECK (capacity >= 1),
    deputy_cap INT    NOT NULL CHECK (deputy_cap >= 0),
    admin_cap  INT    NOT NULL CHECK (admin_cap >= 0)
);
INSERT INTO parivar_levels (level, exp_needed, capacity, deputy_cap, admin_cap) VALUES
 (1, 0, 20, 1, 2), (2, 5000, 25, 1, 3), (3, 20000, 30, 2, 4), (4, 60000, 35, 2, 5),
 (5, 150000, 40, 3, 6), (6, 400000, 45, 3, 8), (7, 1000000, 50, 4, 10);

CREATE TABLE parivars (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(30)  NOT NULL,
    sign         VARCHAR(4)   NOT NULL,
    emblem       VARCHAR(16)  NOT NULL DEFAULT '🪔',
    color        VARCHAR(9)   NOT NULL DEFAULT '#e7bc72',
    logo_url     VARCHAR(500),
    intro        VARCHAR(300),
    announcement VARCHAR(500),
    leader_id    BIGINT       NOT NULL REFERENCES users (id),
    join_mode    VARCHAR(8)   NOT NULL DEFAULT 'OPEN' CHECK (join_mode IN ('OPEN', 'APPROVAL')),
    level        INT          NOT NULL DEFAULT 1,
    exp          BIGINT       NOT NULL DEFAULT 0,
    funds        BIGINT       NOT NULL DEFAULT 0 CHECK (funds >= 0),
    status       VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISBANDED')),
    create_key   VARCHAR(80),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_parivars_name ON parivars (lower(name)) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_parivars_sign ON parivars (lower(sign)) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_parivars_create_key ON parivars (create_key) WHERE create_key IS NOT NULL;

CREATE TABLE parivar_members (
    parivar_id   BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         VARCHAR(8)  NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('LEADER', 'DEPUTY', 'ADMIN', 'MEMBER')),
    contribution BIGINT      NOT NULL DEFAULT 0,
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (parivar_id, user_id)
);
-- (One Parivar per user is a setting, checked by the backend under a per-user lock.)
CREATE INDEX idx_parivar_member_user ON parivar_members (user_id);

CREATE TABLE parivar_applications (
    id          BIGSERIAL PRIMARY KEY,
    parivar_id  BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status      VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN')),
    decided_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_parivar_application_pending ON parivar_applications (parivar_id, user_id) WHERE status = 'PENDING';

-- Who may do what. LEADER is always allowed everything in code; the others are set here.
CREATE TABLE parivar_permissions (
    role    VARCHAR(8)  NOT NULL CHECK (role IN ('DEPUTY', 'ADMIN', 'MEMBER')),
    perm    VARCHAR(30) NOT NULL,
    allowed BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (role, perm)
);
INSERT INTO parivar_permissions (role, perm, allowed)
SELECT r.role, p.perm,
       CASE
         WHEN r.role = 'DEPUTY' THEN p.perm <> 'APPOINT_DEPUTY' AND p.perm <> 'MANAGE_SETTINGS'
         WHEN r.role = 'ADMIN'  THEN p.perm IN ('EDIT_ANNOUNCEMENT', 'ACCEPT_APPLICATIONS', 'REMOVE_MEMBERS')
         ELSE FALSE
       END
FROM (VALUES ('DEPUTY'), ('ADMIN'), ('MEMBER')) AS r (role)
CROSS JOIN (VALUES ('EDIT_PROFILE'), ('EDIT_ANNOUNCEMENT'), ('ACCEPT_APPLICATIONS'), ('REMOVE_MEMBERS'),
                   ('APPOINT_DEPUTY'), ('APPOINT_ADMIN'), ('MANAGE_TREASURY'), ('MANAGE_SETTINGS')) AS p (perm);

-- What each member did for the Parivar (gifts, donations): the activity ledger, and the family activity feed
CREATE TABLE parivar_activity (
    id          BIGSERIAL PRIMARY KEY,
    parivar_id  BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    user_id     BIGINT      NOT NULL,
    other_id    BIGINT,
    kind        VARCHAR(12) NOT NULL,
    exp         BIGINT      NOT NULL DEFAULT 0,
    coins       BIGINT      NOT NULL DEFAULT 0,
    label       VARCHAR(120),
    ref         VARCHAR(100),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_parivar_activity_ref ON parivar_activity (ref) WHERE ref IS NOT NULL;
CREATE INDEX idx_parivar_activity_parivar ON parivar_activity (parivar_id, id DESC);
CREATE INDEX idx_parivar_activity_week ON parivar_activity (created_at, parivar_id);

-- The shared treasury: every change in funds
CREATE TABLE parivar_funds_ledger (
    id            BIGSERIAL PRIMARY KEY,
    parivar_id    BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    user_id       BIGINT,
    delta         BIGINT      NOT NULL,
    balance_after BIGINT      NOT NULL,
    kind          VARCHAR(12) NOT NULL,
    ref           VARCHAR(100),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_parivar_funds_ref ON parivar_funds_ledger (ref) WHERE ref IS NOT NULL;

-- Changes staff made to the Parivar settings
CREATE TABLE parivar_config_audit (
    id         BIGSERIAL PRIMARY KEY,
    admin_name VARCHAR(120),
    what       VARCHAR(60) NOT NULL,
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
