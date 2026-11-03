-- Room points: every coin gifted in a room adds 100 points (room level).
ALTER TABLE voice_rooms ADD COLUMN points BIGINT NOT NULL DEFAULT 0;

-- Every gift sent in a room. Coins are moved by the wallet ledger
-- (coin_transactions); this is the history for boards and the room feed.
CREATE TABLE room_gift_events (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES voice_rooms(id) ON DELETE CASCADE,
    sender_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    receiver_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    gift_id VARCHAR(40) NOT NULL,
    gift_count INTEGER NOT NULL CHECK (gift_count > 0),
    coins BIGINT NOT NULL CHECK (coins > 0),
    receiver_earned BIGINT NOT NULL CHECK (receiver_earned >= 0),
    -- The app's id for this send: a retry with the same id is not charged twice.
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_room_gift_events_request UNIQUE (request_id)
);

CREATE INDEX idx_room_gift_events_room_time ON room_gift_events(room_id, created_at);
CREATE INDEX idx_room_gift_events_receiver ON room_gift_events(receiver_id);
