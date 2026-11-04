-- Room invites: a code a member shares (in a chat) that lets a friend into a
-- password room without the password, for 24 hours.
CREATE TABLE room_invites (
    code       VARCHAR(12) PRIMARY KEY,
    room_id    BIGINT      NOT NULL REFERENCES voice_rooms (id) ON DELETE CASCADE,
    created_by BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_room_invites_room ON room_invites (room_id);

-- A short code for each game table, to join with or share in a chat
ALTER TABLE room_games ADD COLUMN IF NOT EXISTS code VARCHAR(8);
CREATE UNIQUE INDEX IF NOT EXISTS uq_room_games_code ON room_games (code) WHERE code IS NOT NULL;
