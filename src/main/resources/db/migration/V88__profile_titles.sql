-- Profile titles: coloured name tags (like "Shining Star") the admin makes. A person owns some and wears a few on their profile.
CREATE TABLE profile_titles (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(30) NOT NULL,
    icon_url    VARCHAR(500),
    color_from  VARCHAR(9)  NOT NULL DEFAULT '#7b5cff',
    color_to    VARCHAR(9)  NOT NULL DEFAULT '#c56bff',
    text_color  VARCHAR(9)  NOT NULL DEFAULT '#ffffff',
    rarity      VARCHAR(10) NOT NULL DEFAULT 'COMMON' CHECK (rarity IN ('COMMON', 'PREMIUM', 'EPIC', 'LEGENDARY', 'ULTRA_RARE')),
    price_coins BIGINT      NOT NULL DEFAULT 0 CHECK (price_coins >= 0),
    free        BOOLEAN     NOT NULL DEFAULT FALSE,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order  INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE user_titles (
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title_id    BIGINT      NOT NULL REFERENCES profile_titles (id) ON DELETE CASCADE,
    source      VARCHAR(20) NOT NULL,
    worn_pos    INT,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ,
    PRIMARY KEY (user_id, title_id)
);
CREATE INDEX idx_user_titles_title ON user_titles (title_id);
CREATE TABLE title_settings (
    id       INT PRIMARY KEY CHECK (id = 1),
    max_worn INT NOT NULL DEFAULT 3 CHECK (max_worn BETWEEN 1 AND 10)
);
INSERT INTO title_settings (id) VALUES (1);

ALTER TABLE treasure_rewards DROP CONSTRAINT IF EXISTS treasure_rewards_kind_check;
ALTER TABLE treasure_rewards ADD CONSTRAINT treasure_rewards_kind_check CHECK (kind IN ('GIFT', 'ITEM', 'VIP_CARD', 'TITLE', 'BADGE', 'COLLECTIBLE', 'PROFILE_TITLE'));

-- A few to start with: two for everyone, two to buy, two only from chests or the admin
INSERT INTO profile_titles (name, color_from, color_to, rarity, price_coins, free, sort_order) VALUES
    ('Newcomer', '#2fb7a3', '#4fd6c0', 'COMMON', 0, TRUE, 1),
    ('Panchayat Fan', '#3a8dff', '#7a5cff', 'COMMON', 0, TRUE, 2),
    ('Shining Star', '#19c3b2', '#42d6e0', 'PREMIUM', 5000, FALSE, 3),
    ('Richie Rich', '#f0a030', '#ffcf5a', 'EPIC', 20000, FALSE, 4),
    ('All Eyes On', '#7b3fe4', '#c56bff', 'EPIC', 0, FALSE, 5),
    ('Popular Idol', '#3a6dff', '#6fa8ff', 'LEGENDARY', 0, FALSE, 6);
