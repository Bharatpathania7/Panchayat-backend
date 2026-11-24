-- VIP bought through Munshi ji (manual UPI): the request keeps how many VIP days it is for, so a later
-- change to the plan never changes what was paid for. kind = 'VIP', pack_id = the VIP plan id.
ALTER TABLE manual_topups ADD COLUMN IF NOT EXISTS vip_days INTEGER;
