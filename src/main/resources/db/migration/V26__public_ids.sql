-- Public 7-digit IDs people see, share and search by (like 6372289), instead of 1, 2, 3.
-- Each is a fixed scramble of the internal id: unique (the multipliers are coprime with
-- 9,000,000, so ids 0..8,999,999 never collide), never changes, and needs no extra code
-- when a user or room is created. The app uses the same formula (lib/app/public-ids.ts).
ALTER TABLE users
    ADD COLUMN public_id BIGINT GENERATED ALWAYS AS (1000000 + ((id::bigint * 7654321 + 3141592) % 9000000)) STORED;
CREATE UNIQUE INDEX uq_users_public_id ON users (public_id);

ALTER TABLE voice_rooms
    ADD COLUMN room_code BIGINT GENERATED ALWAYS AS (1000000 + ((id::bigint * 4782961 + 7777777) % 9000000)) STORED;
CREATE UNIQUE INDEX uq_voice_rooms_room_code ON voice_rooms (room_code);
