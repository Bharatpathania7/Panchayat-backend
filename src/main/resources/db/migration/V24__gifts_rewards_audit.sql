-- Gifts managed from the admin panel (price, picture, animation, sound).
CREATE TABLE gifts (
    id                  VARCHAR(64) PRIMARY KEY,
    name                VARCHAR(60)  NOT NULL,
    price               BIGINT       NOT NULL CHECK (price > 0),
    category            VARCHAR(30)  NOT NULL DEFAULT 'Popular',
    sort_order          INT          NOT NULL DEFAULT 0,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    image_url           VARCHAR(500),
    image_public_id     VARCHAR(200),
    animation_url       VARCHAR(500),
    animation_public_id VARCHAR(200),
    animation_format    VARCHAR(10),
    sound_url           VARCHAR(500),
    sound_public_id     VARCHAR(200),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The gifts the app already sells, at their current prices
INSERT INTO gifts (id, name, price, category, sort_order) VALUES
    ('city-fireworks',  'City Fireworks',  2000,   'Celebration', 1),
    ('neon-racer',      'Neon Racer',      9999,   'Royal', 2),
    ('love-fireworks',  'Love Fireworks',  13140,  'Romantic',    3),
    ('cupid-heart',     'Cupid Heart',     18888,  'Romantic',    4),
    ('god-of-wealth',   'God of Wealth',   18888,  'Celebration', 5),
    ('dragon',          'Dragon',          19999,  'Legendary',  6),
    ('star-prince',     'Star Prince',     19999,  'Royal', 7),
    ('princess-flower', 'Princess Flower', 39999,  'Romantic',    8),
    ('savanna-king',    'Savanna King',    39999,  'Legendary',  9),
    ('sunset-romance',  'Sunset Romance',  52013,  'Romantic',    10),
    ('crystal-palace',  'Crystal Palace',  131420, 'Legendary',  11),
    ('storm-guardian',  'Storm Guardian',  131420, 'Legendary',  12);

-- Spend rewards. RANDOM: every coin_threshold coins spent, one item at random
-- (priority 1 most likely … 4 least). GUARANTEED: after coin_threshold coins,
-- every enabled item. repeatable = again every coin_threshold coins.
CREATE TABLE reward_rules (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(80) NOT NULL,
    kind           VARCHAR(12) NOT NULL CHECK (kind IN ('RANDOM', 'GUARANTEED')),
    coin_threshold BIGINT      NOT NULL CHECK (coin_threshold > 0),
    repeatable     BOOLEAN     NOT NULL DEFAULT TRUE,
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by     VARCHAR(60),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reward_rule_items (
    id               BIGSERIAL PRIMARY KEY,
    rule_id          BIGINT      NOT NULL REFERENCES reward_rules (id) ON DELETE CASCADE,
    name             VARCHAR(80) NOT NULL,
    item_type        VARCHAR(30) NOT NULL,
    gift_id          VARCHAR(64) REFERENCES gifts (id) ON DELETE SET NULL,
    quantity         INT         NOT NULL DEFAULT 1 CHECK (quantity BETWEEN 1 AND 9999),
    priority         INT         NOT NULL DEFAULT 1 CHECK (priority BETWEEN 1 AND 4),
    enabled          BOOLEAN     NOT NULL DEFAULT TRUE,
    duration_days    INT CHECK (duration_days IS NULL OR duration_days BETWEEN 1 AND 3650),
    media_url        VARCHAR(500),
    media_public_id  VARCHAR(200),
    media_format     VARCHAR(10),
    sound_url        VARCHAR(500),
    sound_public_id  VARCHAR(200),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reward_rule_items_rule ON reward_rule_items (rule_id);

-- Coins each user has spent towards each rule since its last trigger
CREATE TABLE user_spend_progress (
    user_id    BIGINT      NOT NULL,
    rule_id    BIGINT      NOT NULL REFERENCES reward_rules (id) ON DELETE CASCADE,
    spent      BIGINT      NOT NULL DEFAULT 0,
    triggered  INT         NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, rule_id)
);

-- What users won. Item details are copied so a later edit doesn't change past wins.
CREATE TABLE user_rewards (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    rule_id      BIGINT REFERENCES reward_rules (id) ON DELETE SET NULL,
    rule_item_id BIGINT REFERENCES reward_rule_items (id) ON DELETE SET NULL,
    rule_kind    VARCHAR(12) NOT NULL,
    rule_name    VARCHAR(80) NOT NULL,
    item_name    VARCHAR(80) NOT NULL,
    item_type    VARCHAR(30) NOT NULL,
    gift_id      VARCHAR(64),
    quantity     INT         NOT NULL DEFAULT 1,
    media_url    VARCHAR(500),
    media_format VARCHAR(10),
    sound_url    VARCHAR(500),
    granted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ
);
CREATE INDEX idx_user_rewards_user ON user_rewards (user_id, granted_at DESC);
CREATE INDEX idx_user_rewards_granted ON user_rewards (granted_at DESC);

-- Every change made from the admin panel
CREATE TABLE admin_audit_logs (
    id             BIGSERIAL PRIMARY KEY,
    admin_username VARCHAR(60),
    admin_role     VARCHAR(20),
    method         VARCHAR(10)  NOT NULL,
    path           VARCHAR(300) NOT NULL,
    status         INT          NOT NULL,
    ip             VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_audit_created ON admin_audit_logs (created_at DESC);
