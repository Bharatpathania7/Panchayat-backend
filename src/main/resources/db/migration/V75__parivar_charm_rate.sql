-- Gifts inside a Parivar give 1 Charm for this many coins (admin setting, default 5)
ALTER TABLE parivar_settings ADD COLUMN IF NOT EXISTS gift_coins_per_charm INT NOT NULL DEFAULT 5 CHECK (gift_coins_per_charm >= 1);
