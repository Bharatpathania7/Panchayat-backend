-- Gifts sent outside rooms (profile, BFF, CP, Moments): same history table, no room
ALTER TABLE room_gift_events ALTER COLUMN room_id DROP NOT NULL;
ALTER TABLE room_gift_events ADD COLUMN IF NOT EXISTS context VARCHAR(20);
CREATE INDEX IF NOT EXISTS idx_room_gift_events_pair ON room_gift_events (sender_id, receiver_id, created_at DESC);

-- BFF customisation: a pet name and a colour theme the pair chooses
ALTER TABLE user_tags ADD COLUMN IF NOT EXISTS nickname VARCHAR(24);
ALTER TABLE user_tags ADD COLUMN IF NOT EXISTS theme VARCHAR(20);
