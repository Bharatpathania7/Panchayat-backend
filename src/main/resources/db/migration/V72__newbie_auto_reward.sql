-- Welcome reward for new members who enter a welcome room: a gift animation plus coins and Charm, set in the admin panel.
ALTER TABLE newbie_room_config ADD COLUMN IF NOT EXISTS auto_greeting_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE newbie_room_config ADD COLUMN IF NOT EXISTS auto_reward_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE newbie_room_config ADD COLUMN IF NOT EXISTS auto_reward_gift_id VARCHAR(64);
ALTER TABLE newbie_room_config ADD COLUMN IF NOT EXISTS auto_reward_coins BIGINT NOT NULL DEFAULT 0 CHECK (auto_reward_coins BETWEEN 0 AND 1000000);
ALTER TABLE newbie_room_config ADD COLUMN IF NOT EXISTS auto_reward_charm BIGINT NOT NULL DEFAULT 0 CHECK (auto_reward_charm BETWEEN 0 AND 1000000);

-- The reward is given once per person, ever
ALTER TABLE newbie_user_status ADD COLUMN IF NOT EXISTS reward_at TIMESTAMPTZ;
