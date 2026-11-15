-- A locked Family Treasure chest can be opened at once for coins: this many coins for every minute still left (0 = not offered).
-- The admin sets it for each chest in Parivar > Treasure.
ALTER TABLE treasure_tiers ADD COLUMN skip_coins_per_minute BIGINT NOT NULL DEFAULT 0 CHECK (skip_coins_per_minute >= 0);
