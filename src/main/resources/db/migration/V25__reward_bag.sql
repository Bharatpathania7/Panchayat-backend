-- Rewards in the user's Bag.
-- slot: where a worn effect shows (FRAME, BUBBLE, ENTRY, PROFILE); null for gifts
-- and for types the app can't wear. remaining: gifts left to send from the Bag.
ALTER TABLE user_rewards ADD COLUMN slot VARCHAR(12);
ALTER TABLE user_rewards ADD COLUMN equipped BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_rewards ADD COLUMN remaining INT;

UPDATE user_rewards SET remaining = quantity WHERE item_type = 'GIFT';
UPDATE user_rewards SET slot = CASE
    WHEN item_type = 'GIFT' THEN NULL
    WHEN item_type LIKE '%BUBBLE%' THEN 'BUBBLE'
    WHEN item_type LIKE '%ENTRY%' THEN 'ENTRY'
    WHEN item_type LIKE '%PROFILE%' THEN 'PROFILE'
    WHEN item_type LIKE '%FRAME%' THEN 'FRAME'
    ELSE NULL END;

-- One worn item per slot per user
CREATE UNIQUE INDEX uq_user_rewards_worn ON user_rewards (user_id, slot) WHERE equipped;
CREATE INDEX idx_user_rewards_bag_gifts ON user_rewards (user_id, gift_id) WHERE remaining > 0;

-- Gifts sent from the Bag cost no coins
ALTER TABLE room_gift_events ADD COLUMN from_bag BOOLEAN NOT NULL DEFAULT FALSE;

-- Admin-made gift ids can be up to 64 characters
ALTER TABLE room_gift_events ALTER COLUMN gift_id TYPE VARCHAR(64);
