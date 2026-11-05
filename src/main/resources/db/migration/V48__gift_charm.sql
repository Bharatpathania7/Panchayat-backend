-- The charm each gift gave (so a sender's Guard equals the Charm they gave, weekends x2 included).
ALTER TABLE room_gift_events ADD COLUMN IF NOT EXISTS charm BIGINT;
UPDATE room_gift_events SET charm = coins / 5 WHERE charm IS NULL;
