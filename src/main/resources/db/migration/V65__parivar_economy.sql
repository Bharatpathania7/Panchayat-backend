-- Parivar part 2: Kavach (barrier), Chori (steal), cheer, Gacha, chests, shop, tasks, news, chat, voice room, ranking rewards.
-- Every number here is edited from the admin panel (Parivar management).

ALTER TABLE parivar_settings
    ADD COLUMN cheer_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN steal_enabled           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN steal_min_level         INT     NOT NULL DEFAULT 1,
    ADD COLUMN steal_daily_attempts    INT     NOT NULL DEFAULT 3,
    ADD COLUMN steal_cooldown_minutes  INT     NOT NULL DEFAULT 30,
    ADD COLUMN steal_success_percent   INT     NOT NULL DEFAULT 40 CHECK (steal_success_percent BETWEEN 0 AND 100),
    ADD COLUMN steal_take_percent      NUMERIC(5, 2) NOT NULL DEFAULT 5 CHECK (steal_take_percent BETWEEN 0 AND 100),
    ADD COLUMN steal_max_take          BIGINT  NOT NULL DEFAULT 50000,
    ADD COLUMN steal_fail_penalty      BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN steal_min_target_funds  BIGINT  NOT NULL DEFAULT 1000,
    ADD COLUMN gacha_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN shop_enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN tasks_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN chat_enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN voice_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN voice_min_level         INT     NOT NULL DEFAULT 2,
    ADD COLUMN chest_expiry_hours      INT     NOT NULL DEFAULT 24,
    -- what happens to chest pieces nobody claimed: NONE, or REFUND_FUNDS (their share of the price goes back to the treasury)
    ADD COLUMN chest_expiry_policy     VARCHAR(12) NOT NULL DEFAULT 'REFUND_FUNDS',
    ADD COLUMN checkin_exp             INT     NOT NULL DEFAULT 50,
    ADD COLUMN rank_rewards_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN last_rank_week          DATE;

-- The leader's switch: may the members use Chori for this Parivar?
ALTER TABLE parivars ADD COLUMN steal_on BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE parivar_activity ADD COLUMN qty INT NOT NULL DEFAULT 1;

-- More things a role can be allowed to do (the Leader can always do everything)
ALTER TABLE parivar_permissions DROP CONSTRAINT IF EXISTS parivar_permissions_perm_check;
INSERT INTO parivar_permissions (role, perm, allowed)
SELECT r.role, p.perm,
       CASE WHEN r.role = 'DEPUTY' THEN p.perm <> 'MANAGE_STEAL' ELSE p.perm IN ('MANAGE_GACHA') AND r.role = 'ADMIN' END
FROM (VALUES ('DEPUTY'), ('ADMIN'), ('MEMBER')) AS r (role)
CROSS JOIN (VALUES ('USE_STEAL'), ('MANAGE_STEAL'), ('MANAGE_BARRIER'), ('MANAGE_GACHA'), ('MANAGE_CHESTS'), ('MANAGE_VOICE')) AS p (perm)
ON CONFLICT DO NOTHING;

-- What a Parivar owns besides funds: Gacha cards, chests, barriers
CREATE TABLE parivar_inventory (
    parivar_id BIGINT NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    kind       VARCHAR(12) NOT NULL CHECK (kind IN ('GACHA_CARD', 'CHEST', 'BARRIER')),
    ref        BIGINT NOT NULL,
    qty        BIGINT NOT NULL DEFAULT 0 CHECK (qty >= 0),
    PRIMARY KEY (parivar_id, kind, ref)
);

-- Kavach (barrier) types
CREATE TABLE parivar_barrier_types (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(40) NOT NULL,
    image_url        VARCHAR(500),
    strength         INT         NOT NULL DEFAULT 50 CHECK (strength BETWEEN 0 AND 100),
    duration_minutes INT         NOT NULL DEFAULT 720 CHECK (duration_minutes >= 1),
    price_funds      BIGINT      NOT NULL DEFAULT 1000 CHECK (price_funds >= 0),
    cooldown_minutes INT         NOT NULL DEFAULT 0 CHECK (cooldown_minutes >= 0),
    daily_limit      INT         NOT NULL DEFAULT 2 CHECK (daily_limit >= 0),
    min_level        INT         NOT NULL DEFAULT 1,
    sort_order       INT         NOT NULL DEFAULT 0,
    active           BOOLEAN     NOT NULL DEFAULT TRUE
);
INSERT INTO parivar_barrier_types (name, strength, duration_minutes, price_funds, cooldown_minutes, daily_limit, min_level, sort_order) VALUES
 ('Saadha Kavach', 40, 720, 1000, 0, 2, 1, 1), ('Mazboot Kavach', 70, 1440, 5000, 60, 2, 2, 2),
 ('Shahi Kavach', 90, 2880, 20000, 240, 1, 3, 3);

CREATE TABLE parivar_barriers (
    id           BIGSERIAL PRIMARY KEY,
    parivar_id   BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    type_id      BIGINT      NOT NULL,
    name         VARCHAR(40) NOT NULL,
    strength     INT         NOT NULL,
    activated_by BIGINT,
    starts_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    notified     BOOLEAN     NOT NULL DEFAULT FALSE,
    ref_key      VARCHAR(100)
);
CREATE UNIQUE INDEX uq_parivar_barrier_key ON parivar_barriers (ref_key) WHERE ref_key IS NOT NULL;
CREATE INDEX idx_parivar_barriers_active ON parivar_barriers (parivar_id, expires_at DESC);

-- Every Chori attempt
CREATE TABLE parivar_steal_log (
    id             BIGSERIAL PRIMARY KEY,
    thief_parivar  BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    thief_user     BIGINT      NOT NULL,
    target_parivar BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    result         VARCHAR(8)  NOT NULL CHECK (result IN ('SUCCESS', 'FAILED', 'BLOCKED')),
    amount         BIGINT      NOT NULL DEFAULT 0,
    barrier_strength INT       NOT NULL DEFAULT 0,
    ref            VARCHAR(100),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_parivar_steal_ref ON parivar_steal_log (ref) WHERE ref IS NOT NULL;
CREATE INDEX idx_parivar_steal_thief ON parivar_steal_log (thief_parivar, created_at DESC);
CREATE INDEX idx_parivar_steal_target ON parivar_steal_log (target_parivar, created_at DESC);

-- Cheer (x1, x10, x100)
CREATE TABLE parivar_cheer_tiers (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(30) NOT NULL,
    times        INT         NOT NULL CHECK (times >= 1),
    coins_cost   BIGINT      NOT NULL CHECK (coins_cost >= 0),
    exp_gain     BIGINT      NOT NULL DEFAULT 0 CHECK (exp_gain >= 0),
    funds_gain   BIGINT      NOT NULL DEFAULT 0 CHECK (funds_gain >= 0),
    daily_limit  INT         NOT NULL DEFAULT 20 CHECK (daily_limit >= 0),
    sort_order   INT         NOT NULL DEFAULT 0,
    active       BOOLEAN     NOT NULL DEFAULT TRUE
);
INSERT INTO parivar_cheer_tiers (name, times, coins_cost, exp_gain, funds_gain, daily_limit, sort_order) VALUES
 ('Cheer', 1, 100, 10, 0, 50, 1), ('Cheer x10', 10, 1000, 110, 0, 20, 2), ('Cheer x100', 100, 10000, 1200, 0, 5, 3);

-- Gacha machines and what is inside them
CREATE TABLE parivar_gacha_machines (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(40) NOT NULL,
    tier             VARCHAR(12) NOT NULL DEFAULT 'MEDIUM',
    image_url        VARCHAR(500),
    animation_url    VARCHAR(500),
    price_funds      BIGINT      NOT NULL DEFAULT 1000 CHECK (price_funds >= 0),
    service_fee_coins BIGINT     NOT NULL DEFAULT 0 CHECK (service_fee_coins >= 0),
    free_per_day     INT         NOT NULL DEFAULT 0 CHECK (free_per_day >= 0),
    min_level        INT         NOT NULL DEFAULT 1,
    sort_order       INT         NOT NULL DEFAULT 0,
    active           BOOLEAN     NOT NULL DEFAULT TRUE
);
CREATE TABLE parivar_gacha_rewards (
    id         BIGSERIAL PRIMARY KEY,
    machine_id BIGINT      NOT NULL REFERENCES parivar_gacha_machines (id) ON DELETE CASCADE,
    kind       VARCHAR(12) NOT NULL CHECK (kind IN ('COINS', 'FUNDS', 'CHARM', 'CHEST', 'CARD', 'BARRIER')),
    ref        BIGINT      NOT NULL DEFAULT 0,
    amount     BIGINT      NOT NULL DEFAULT 1 CHECK (amount >= 1),
    weight     INT         NOT NULL CHECK (weight >= 1),
    rarity     VARCHAR(10) NOT NULL DEFAULT 'COMMON',
    label      VARCHAR(60) NOT NULL,
    image_url  VARCHAR(500),
    active     BOOLEAN     NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_parivar_gacha_rewards_machine ON parivar_gacha_rewards (machine_id);
CREATE TABLE parivar_gacha_log (
    id         BIGSERIAL PRIMARY KEY,
    parivar_id BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL,
    machine_id BIGINT      NOT NULL,
    reward_id  BIGINT,
    kind       VARCHAR(12) NOT NULL,
    amount     BIGINT      NOT NULL,
    label      VARCHAR(60) NOT NULL,
    rarity     VARCHAR(10),
    paid_with  VARCHAR(8)  NOT NULL,
    ref        VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_parivar_gacha_ref ON parivar_gacha_log (ref) WHERE ref IS NOT NULL;
CREATE INDEX idx_parivar_gacha_log ON parivar_gacha_log (parivar_id, id DESC);

INSERT INTO parivar_gacha_machines (name, tier, price_funds, service_fee_coins, free_per_day, min_level, sort_order) VALUES
 ('Medium Gacha', 'MEDIUM', 1000, 0, 1, 1, 1), ('Advanced Gacha', 'ADVANCED', 5000, 0, 0, 2, 2), ('Super Gacha', 'SUPER', 20000, 0, 0, 4, 3);
INSERT INTO parivar_gacha_rewards (machine_id, kind, amount, weight, rarity, label)
SELECT m.id, r.kind, r.amount, r.weight, r.rarity, r.label
FROM parivar_gacha_machines m
JOIN (VALUES ('MEDIUM', 'COINS', 200, 600, 'COMMON', '200 coins'), ('MEDIUM', 'FUNDS', 300, 250, 'COMMON', '300 Parivar funds'),
             ('MEDIUM', 'CHARM', 100, 100, 'RARE', '100 Charm'), ('MEDIUM', 'COINS', 5000, 40, 'EPIC', '5,000 coins'),
             ('MEDIUM', 'FUNDS', 5000, 10, 'LEGENDARY', '5,000 Parivar funds'),
             ('ADVANCED', 'COINS', 1000, 600, 'COMMON', '1,000 coins'), ('ADVANCED', 'FUNDS', 2000, 250, 'COMMON', '2,000 Parivar funds'),
             ('ADVANCED', 'CHARM', 500, 100, 'RARE', '500 Charm'), ('ADVANCED', 'COINS', 30000, 40, 'EPIC', '30,000 coins'),
             ('ADVANCED', 'FUNDS', 30000, 10, 'LEGENDARY', '30,000 Parivar funds'),
             ('SUPER', 'COINS', 5000, 600, 'COMMON', '5,000 coins'), ('SUPER', 'FUNDS', 10000, 250, 'COMMON', '10,000 Parivar funds'),
             ('SUPER', 'CHARM', 2000, 100, 'RARE', '2,000 Charm'), ('SUPER', 'COINS', 100000, 40, 'EPIC', '1,00,000 coins'),
             ('SUPER', 'FUNDS', 100000, 10, 'LEGENDARY', '1,00,000 Parivar funds')) AS r (tier, kind, amount, weight, rarity, label)
  ON r.tier = m.tier;

-- Chests: bought into the Parivar's inventory, sent to the members, claimed one piece each
CREATE TABLE parivar_chest_types (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(40) NOT NULL,
    image_url   VARCHAR(500),
    price_funds BIGINT      NOT NULL DEFAULT 2000 CHECK (price_funds >= 0),
    pieces      INT         NOT NULL DEFAULT 10 CHECK (pieces >= 1),
    min_level   INT         NOT NULL DEFAULT 1,
    sort_order  INT         NOT NULL DEFAULT 0,
    active      BOOLEAN     NOT NULL DEFAULT TRUE
);
CREATE TABLE parivar_chest_rewards (
    id        BIGSERIAL PRIMARY KEY,
    chest_id  BIGINT      NOT NULL REFERENCES parivar_chest_types (id) ON DELETE CASCADE,
    kind      VARCHAR(12) NOT NULL CHECK (kind IN ('COINS', 'FUNDS', 'CHARM', 'CHEST', 'CARD', 'BARRIER')),
    ref       BIGINT      NOT NULL DEFAULT 0,
    amount    BIGINT      NOT NULL DEFAULT 1 CHECK (amount >= 1),
    weight    INT         NOT NULL CHECK (weight >= 1),
    label     VARCHAR(60) NOT NULL,
    active    BOOLEAN     NOT NULL DEFAULT TRUE
);
INSERT INTO parivar_chest_types (name, price_funds, pieces, min_level, sort_order) VALUES ('Normal Chest', 2000, 10, 1, 1), ('Rare Chest', 10000, 10, 3, 2);
INSERT INTO parivar_chest_rewards (chest_id, kind, amount, weight, label)
SELECT c.id, r.kind, r.amount, r.weight, r.label FROM parivar_chest_types c
JOIN (VALUES ('Normal Chest', 'COINS', 100, 70, '100 coins'), ('Normal Chest', 'CHARM', 50, 25, '50 Charm'), ('Normal Chest', 'COINS', 1000, 5, '1,000 coins'),
             ('Rare Chest', 'COINS', 500, 70, '500 coins'), ('Rare Chest', 'CHARM', 300, 25, '300 Charm'), ('Rare Chest', 'COINS', 10000, 5, '10,000 coins')) AS r (name, kind, amount, weight, label)
  ON r.name = c.name;

CREATE TABLE parivar_chests (
    id         BIGSERIAL PRIMARY KEY,
    parivar_id BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    type_id    BIGINT      NOT NULL,
    name       VARCHAR(40) NOT NULL,
    price      BIGINT      NOT NULL DEFAULT 0,
    pieces     INT         NOT NULL,
    claimed    INT         NOT NULL DEFAULT 0,
    sent_by    BIGINT,
    send_key   VARCHAR(100),
    status     VARCHAR(8)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_parivar_chests ON parivar_chests (parivar_id, id DESC);
CREATE UNIQUE INDEX uq_parivar_chest_key ON parivar_chests (send_key) WHERE send_key IS NOT NULL;
CREATE TABLE parivar_chest_claims (
    chest_id   BIGINT      NOT NULL REFERENCES parivar_chests (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL,
    kind       VARCHAR(12) NOT NULL,
    amount     BIGINT      NOT NULL,
    label      VARCHAR(60) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chest_id, user_id)
);

-- Parivar shop: items the Parivar buys with its funds (or a member buys with coins) into the Parivar inventory
CREATE TABLE parivar_shop_items (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(40) NOT NULL,
    image_url     VARCHAR(500),
    kind          VARCHAR(12) NOT NULL CHECK (kind IN ('GACHA_CARD', 'CHEST', 'BARRIER')),
    ref           BIGINT      NOT NULL,
    qty           INT         NOT NULL DEFAULT 1 CHECK (qty >= 1),
    price_funds   BIGINT      NOT NULL DEFAULT 0 CHECK (price_funds >= 0),
    price_coins   BIGINT      NOT NULL DEFAULT 0 CHECK (price_coins >= 0),
    daily_limit   INT         NOT NULL DEFAULT 0 CHECK (daily_limit >= 0),
    min_level     INT         NOT NULL DEFAULT 1,
    sort_order    INT         NOT NULL DEFAULT 0,
    active        BOOLEAN     NOT NULL DEFAULT TRUE
);
INSERT INTO parivar_shop_items (name, kind, ref, qty, price_funds, price_coins, sort_order)
SELECT 'Gacha Card: ' || name, 'GACHA_CARD', id, 1, price_funds, 0, id FROM parivar_gacha_machines;
INSERT INTO parivar_shop_items (name, kind, ref, qty, price_funds, price_coins, sort_order)
SELECT name, 'CHEST', id, 1, price_funds, 0, 10 + id FROM parivar_chest_types;
INSERT INTO parivar_shop_items (name, kind, ref, qty, price_funds, price_coins, sort_order)
SELECT name, 'BARRIER', id, 1, price_funds, 0, 20 + id FROM parivar_barrier_types;
CREATE TABLE parivar_shop_log (
    id         BIGSERIAL PRIMARY KEY,
    parivar_id BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL,
    item_id    BIGINT      NOT NULL,
    qty        INT         NOT NULL,
    funds      BIGINT      NOT NULL DEFAULT 0,
    coins      BIGINT      NOT NULL DEFAULT 0,
    ref        VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_parivar_shop_ref ON parivar_shop_log (ref) WHERE ref IS NOT NULL;

-- Daily and weekly tasks (a member earns the reward by claiming it once per period)
CREATE TABLE parivar_tasks (
    id            BIGSERIAL PRIMARY KEY,
    title         VARCHAR(60) NOT NULL,
    period        VARCHAR(6)  NOT NULL DEFAULT 'DAILY' CHECK (period IN ('DAILY', 'WEEKLY')),
    -- what is counted: GIFT_EXP, DONATED_COINS, CHEERS, CHECKINS, ACTIVITY_EXP, GACHA_ROLLS
    metric        VARCHAR(16) NOT NULL,
    target        BIGINT      NOT NULL CHECK (target >= 1),
    reward_kind   VARCHAR(8)  NOT NULL CHECK (reward_kind IN ('EXP', 'FUNDS', 'COINS', 'CHARM')),
    reward_amount BIGINT      NOT NULL CHECK (reward_amount >= 1),
    sort_order    INT         NOT NULL DEFAULT 0,
    active        BOOLEAN     NOT NULL DEFAULT TRUE
);
INSERT INTO parivar_tasks (title, period, metric, target, reward_kind, reward_amount, sort_order) VALUES
 ('Check in today', 'DAILY', 'CHECKINS', 1, 'EXP', 20, 1),
 ('Send gifts to members (500 EXP)', 'DAILY', 'GIFT_EXP', 500, 'COINS', 200, 2),
 ('Cheer your Parivar', 'DAILY', 'CHEERS', 5, 'EXP', 50, 3),
 ('Donate 1,000 coins this week', 'WEEKLY', 'DONATED_COINS', 1000, 'FUNDS', 200, 4),
 ('Earn 5,000 activity this week', 'WEEKLY', 'ACTIVITY_EXP', 5000, 'CHARM', 100, 5);
CREATE TABLE parivar_task_claims (
    task_id    BIGINT      NOT NULL REFERENCES parivar_tasks (id) ON DELETE CASCADE,
    parivar_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    period_key VARCHAR(12) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, user_id, period_key)
);

-- News posts (announcements, rules, events) with read marks
CREATE TABLE parivar_news (
    id         BIGSERIAL PRIMARY KEY,
    parivar_id BIGINT       NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    author_id  BIGINT       NOT NULL,
    kind       VARCHAR(8)   NOT NULL DEFAULT 'NOTICE' CHECK (kind IN ('NOTICE', 'RULE', 'EVENT')),
    title      VARCHAR(80)  NOT NULL,
    body       VARCHAR(1000) NOT NULL,
    pinned     BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_parivar_news ON parivar_news (parivar_id, pinned DESC, id DESC);
CREATE TABLE parivar_news_reads (
    news_id BIGINT NOT NULL REFERENCES parivar_news (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (news_id, user_id)
);

-- Family chat and each member's chat settings
CREATE TABLE parivar_chat (
    id         BIGSERIAL PRIMARY KEY,
    parivar_id BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    user_id    BIGINT,
    kind       VARCHAR(8)  NOT NULL DEFAULT 'TEXT' CHECK (kind IN ('TEXT', 'SYSTEM')),
    text       VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_parivar_chat ON parivar_chat (parivar_id, id DESC);
CREATE TABLE parivar_member_prefs (
    parivar_id BIGINT  NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    user_id    BIGINT  NOT NULL,
    muted      BOOLEAN NOT NULL DEFAULT FALSE,
    fold_gifts BOOLEAN NOT NULL DEFAULT FALSE,
    gift_sound BOOLEAN NOT NULL DEFAULT TRUE,
    background VARCHAR(20) NOT NULL DEFAULT 'default',
    PRIMARY KEY (parivar_id, user_id)
);

-- Parivar voice room: a normal voice room that only members can enter
ALTER TABLE voice_rooms ADD COLUMN parivar_id BIGINT REFERENCES parivars (id) ON DELETE SET NULL;

-- Weekly ranking rewards: funds for the Parivars at these places, paid when the week ends
CREATE TABLE parivar_rank_rewards (
    id          BIGSERIAL PRIMARY KEY,
    rank_from   INT    NOT NULL CHECK (rank_from >= 1),
    rank_to     INT    NOT NULL CHECK (rank_to >= rank_from),
    funds       BIGINT NOT NULL CHECK (funds >= 0),
    active      BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO parivar_rank_rewards (rank_from, rank_to, funds) VALUES (1, 1, 50000), (2, 2, 30000), (3, 3, 20000), (4, 10, 5000);
