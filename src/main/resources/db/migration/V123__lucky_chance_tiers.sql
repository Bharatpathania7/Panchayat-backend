-- Lucky coins: the chance depends on what one gift costs. Text "minCoins:percent;minCoins:percent" (the highest minCoins a gift reaches wins).
ALTER TABLE gift_settings ADD COLUMN IF NOT EXISTS lucky_tiers TEXT NOT NULL DEFAULT '';
