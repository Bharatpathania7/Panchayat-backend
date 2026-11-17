-- Shop items that are not worn (kept in the Bag) can also run out after the product's "lasts (days)".
ALTER TABLE user_shop_items ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
