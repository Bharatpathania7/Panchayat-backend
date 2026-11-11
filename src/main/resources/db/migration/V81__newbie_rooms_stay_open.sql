-- Welcome rooms stay open: a lifetime of 0 hours means the room never closes by itself (it closes only when the admin closes it).
ALTER TABLE newbie_room_config DROP CONSTRAINT IF EXISTS newbie_room_config_room_lifetime_hours_check;
ALTER TABLE newbie_room_config ADD CONSTRAINT newbie_room_config_room_lifetime_hours_check CHECK (room_lifetime_hours BETWEEN 0 AND 720);
UPDATE newbie_room_config SET room_lifetime_hours = 0 WHERE id = 1;
UPDATE newbie_rooms SET expires_at = NULL WHERE status = 'ACTIVE';
