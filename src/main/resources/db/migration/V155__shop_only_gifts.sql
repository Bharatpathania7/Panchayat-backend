-- A Shop-only gift: made from the Shop's product form, never in the gift panel. People get it only by buying it in the
-- Shop (it lands in their Package) and send it from there.
ALTER TABLE gifts ADD COLUMN IF NOT EXISTS shop_only BOOLEAN NOT NULL DEFAULT FALSE;
