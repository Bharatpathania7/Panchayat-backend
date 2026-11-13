-- Parivar library: animations, badges, titles and gift art the admin uploads once, then puts into Family Treasure chests as rewards.
CREATE TABLE parivar_collectibles (
    id           BIGSERIAL PRIMARY KEY,
    kind         VARCHAR(10) NOT NULL CHECK (kind IN ('BADGE', 'TITLE', 'ANIMATION', 'GIFT')),
    name         VARCHAR(60) NOT NULL,
    description  VARCHAR(200),
    media_url    VARCHAR(500) NOT NULL,
    media_format VARCHAR(10) NOT NULL,
    rarity       VARCHAR(10) NOT NULL DEFAULT 'COMMON' CHECK (rarity IN ('COMMON', 'PREMIUM', 'EPIC', 'LEGENDARY', 'ULTRA_RARE')),
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE treasure_rewards DROP CONSTRAINT IF EXISTS treasure_rewards_kind_check;
ALTER TABLE treasure_rewards ADD CONSTRAINT treasure_rewards_kind_check CHECK (kind IN ('GIFT', 'ITEM', 'VIP_CARD', 'TITLE', 'BADGE', 'COLLECTIBLE'));
ALTER TABLE user_collection DROP CONSTRAINT IF EXISTS user_collection_kind_check;
ALTER TABLE user_collection ADD CONSTRAINT user_collection_kind_check CHECK (kind IN ('TITLE', 'BADGE', 'ANIMATION', 'GIFT'));
