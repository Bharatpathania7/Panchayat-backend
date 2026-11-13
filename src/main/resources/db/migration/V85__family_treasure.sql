-- Family Treasure: weekly activity unlocks a chest (Normal / Advanced / Rare / Super), the family claims it into its Treasure,
-- a timer runs, then the Leader or Deputy sends it (to everyone by luck, to one member, or takes it all).
-- Everything a person can win and every number below is set in the admin panel.

-- Diamond wallet
CREATE TABLE diamond_wallets (
    user_id    BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    balance    BIGINT      NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE diamond_transactions (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    amount        BIGINT      NOT NULL,
    balance_after BIGINT      NOT NULL,
    reason        VARCHAR(40) NOT NULL,
    ref           VARCHAR(120),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_diamond_tx_ref ON diamond_transactions (ref) WHERE ref IS NOT NULL;
CREATE INDEX idx_diamond_tx_user ON diamond_transactions (user_id, id DESC);

-- Titles and badges a person owns (the profile collection)
CREATE TABLE user_collection (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       VARCHAR(10) NOT NULL CHECK (kind IN ('TITLE', 'BADGE')),
    label      VARCHAR(60) NOT NULL,
    image_url  VARCHAR(500),
    source     VARCHAR(40) NOT NULL,
    ref        VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_user_collection_ref ON user_collection (ref) WHERE ref IS NOT NULL;
CREATE INDEX idx_user_collection_user ON user_collection (user_id, id DESC);

-- One row per chest tier. Weekly target is the Parivar's weekly activity (the same number as the Parivar ranking).
CREATE TABLE treasure_tiers (
    id                 BIGSERIAL PRIMARY KEY,
    code               VARCHAR(12) NOT NULL UNIQUE,
    name               VARCHAR(40) NOT NULL,
    enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    weekly_target      BIGINT      NOT NULL CHECK (weekly_target >= 1),
    unlock_minutes     INT         NOT NULL DEFAULT 60 CHECK (unlock_minutes >= 0),
    pool_coins         BIGINT      NOT NULL DEFAULT 0 CHECK (pool_coins >= 0),
    pool_diamonds      BIGINT      NOT NULL DEFAULT 0 CHECK (pool_diamonds >= 0),
    draws_per_member   INT         NOT NULL DEFAULT 1 CHECK (draws_per_member BETWEEN 0 AND 10),
    eligible_min_hours INT         NOT NULL DEFAULT 0 CHECK (eligible_min_hours >= 0),
    image_url          VARCHAR(500),
    sort_order         INT         NOT NULL DEFAULT 0
);

-- What can be inside. kind: GIFT (gift_id), ITEM (ref = a frame / bubble / entry effect / decoration / outfit made in Spend rewards),
-- VIP_CARD (amount = days), TITLE, BADGE (label is what the person gets). Weight is the chance; max_per_chest is the limit (0 = no limit).
CREATE TABLE treasure_rewards (
    id            BIGSERIAL PRIMARY KEY,
    tier_id       BIGINT      NOT NULL REFERENCES treasure_tiers (id) ON DELETE CASCADE,
    kind          VARCHAR(10) NOT NULL CHECK (kind IN ('GIFT', 'ITEM', 'VIP_CARD', 'TITLE', 'BADGE')),
    ref           BIGINT      NOT NULL DEFAULT 0,
    gift_id       VARCHAR(64),
    amount        BIGINT      NOT NULL DEFAULT 1 CHECK (amount >= 0),
    rarity        VARCHAR(10) NOT NULL DEFAULT 'COMMON' CHECK (rarity IN ('COMMON', 'PREMIUM', 'EPIC', 'LEGENDARY', 'ULTRA_RARE')),
    weight        INT         NOT NULL CHECK (weight >= 1),
    max_per_chest INT         NOT NULL DEFAULT 0 CHECK (max_per_chest >= 0),
    label         VARCHAR(60) NOT NULL,
    image_url     VARCHAR(500),
    active        BOOLEAN     NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_treasure_rewards_tier ON treasure_rewards (tier_id);

CREATE TABLE treasure_chests (
    id            BIGSERIAL PRIMARY KEY,
    parivar_id    BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    tier_id       BIGINT      NOT NULL,
    tier_code     VARCHAR(12) NOT NULL,
    name          VARCHAR(40) NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'TREASURE', 'OPENED')),
    grant_key     VARCHAR(100) NOT NULL UNIQUE,
    pool_coins    BIGINT      NOT NULL,
    pool_diamonds BIGINT      NOT NULL,
    earned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_by    BIGINT,
    claimed_at    TIMESTAMPTZ,
    unlock_at     TIMESTAMPTZ,
    opened_at     TIMESTAMPTZ
);
CREATE INDEX idx_treasure_chests_parivar ON treasure_chests (parivar_id, id DESC);

CREATE TABLE treasure_distributions (
    id             BIGSERIAL PRIMARY KEY,
    chest_id       BIGINT      NOT NULL UNIQUE REFERENCES treasure_chests (id) ON DELETE CASCADE,
    parivar_id     BIGINT      NOT NULL,
    tier_code      VARCHAR(12) NOT NULL,
    chest_name     VARCHAR(40) NOT NULL,
    mode           VARCHAR(10) NOT NULL CHECK (mode IN ('ALL', 'TARGET', 'TAKE_ALL')),
    sent_by        BIGINT      NOT NULL,
    member_count   INT         NOT NULL,
    total_coins    BIGINT      NOT NULL,
    total_diamonds BIGINT      NOT NULL,
    top_user       BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_treasure_dist_parivar ON treasure_distributions (parivar_id, id DESC);

CREATE TABLE treasure_payouts (
    id              BIGSERIAL PRIMARY KEY,
    distribution_id BIGINT      NOT NULL REFERENCES treasure_distributions (id) ON DELETE CASCADE,
    chest_id        BIGINT      NOT NULL,
    parivar_id      BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    coins           BIGINT      NOT NULL DEFAULT 0,
    diamonds        BIGINT      NOT NULL DEFAULT 0,
    items           TEXT        NOT NULL DEFAULT '[]',
    score           BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (distribution_id, user_id)
);
CREATE INDEX idx_treasure_payouts_user ON treasure_payouts (user_id, id DESC);

-- Who has already watched the opening (so everyone sees it once)
CREATE TABLE treasure_views (
    distribution_id BIGINT NOT NULL REFERENCES treasure_distributions (id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL,
    PRIMARY KEY (distribution_id, user_id)
);

CREATE TABLE treasure_audit (
    id         BIGSERIAL PRIMARY KEY,
    chest_id   BIGINT,
    parivar_id BIGINT,
    user_id    BIGINT,
    action     VARCHAR(20) NOT NULL,
    detail     VARCHAR(400),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_treasure_audit ON treasure_audit (id DESC);

-- Default tiers. The old "chest at a weekly milestone" is replaced by these, so switch the old milestones off.
UPDATE parivar_activity_milestones SET active = FALSE;
INSERT INTO treasure_tiers (code, name, weekly_target, unlock_minutes, pool_coins, pool_diamonds, draws_per_member, sort_order) VALUES
    ('NORMAL',   'Normal Chest',   8000,   30,  20000,   20, 1, 1),
    ('ADVANCED', 'Advanced Chest', 24000,  60,  80000,   100, 1, 2),
    ('RARE',     'Rare Chest',     60000,  120, 300000,  500, 1, 3),
    ('SUPER',    'Super Chest',    120000, 240, 1500000, 3000, 1, 4);
INSERT INTO treasure_rewards (tier_id, kind, amount, rarity, weight, max_per_chest, label)
SELECT t.id, r.kind, r.amount, r.rarity, r.weight, r.mx, r.label FROM treasure_tiers t JOIN (VALUES
    ('NORMAL', 'BADGE', 1, 'COMMON', 60, 0, 'Family Helper badge'),
    ('NORMAL', 'VIP_CARD', 1, 'PREMIUM', 30, 2, 'VIP card (1 day)'),
    ('ADVANCED', 'BADGE', 1, 'COMMON', 45, 0, 'Family Star badge'),
    ('ADVANCED', 'VIP_CARD', 3, 'PREMIUM', 35, 3, 'VIP card (3 days)'),
    ('ADVANCED', 'TITLE', 1, 'EPIC', 8, 1, 'Family Hero title'),
    ('RARE', 'BADGE', 1, 'PREMIUM', 40, 0, 'Family Champion badge'),
    ('RARE', 'VIP_CARD', 7, 'EPIC', 30, 3, 'VIP card (7 days)'),
    ('RARE', 'TITLE', 1, 'LEGENDARY', 6, 1, 'Family Legend title'),
    ('SUPER', 'BADGE', 1, 'EPIC', 40, 0, 'Royal Family badge'),
    ('SUPER', 'VIP_CARD', 30, 'LEGENDARY', 12, 2, 'VIP card (30 days)'),
    ('SUPER', 'TITLE', 1, 'ULTRA_RARE', 3, 1, 'Dragon Sovereign title')
) AS r (code, kind, amount, rarity, weight, mx, label) ON r.code = t.code;
