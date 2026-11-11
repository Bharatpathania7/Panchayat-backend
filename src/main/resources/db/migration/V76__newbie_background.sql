-- A picture for the Newbie Welcome Rooms: the admin's default, and the one a host can put on their own room.
ALTER TABLE newbie_room_config ADD COLUMN IF NOT EXISTS background_url VARCHAR(500);
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS newbie_background VARCHAR(500);
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS newbie_bg_custom BOOLEAN NOT NULL DEFAULT FALSE;

-- Gifts in rooms give 1 Charm for this many coins (admin setting, locked at 5 by default; 0 = each gift's own numbers)
ALTER TABLE gift_settings ADD COLUMN IF NOT EXISTS coins_per_charm INT NOT NULL DEFAULT 5 CHECK (coins_per_charm >= 0);
