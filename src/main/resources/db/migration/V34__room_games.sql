-- Games played inside rooms (Jackaroo first). The state is kept as JSON and
-- changed under a row lock; hands are only ever sent to their own player.
CREATE TABLE room_games (
    id         BIGSERIAL PRIMARY KEY,
    room_id    BIGINT      NOT NULL REFERENCES voice_rooms (id) ON DELETE CASCADE,
    game       VARCHAR(20) NOT NULL,                -- JACKAROO
    status     VARCHAR(12) NOT NULL DEFAULT 'WAITING', -- WAITING PLAYING FINISHED CANCELLED
    created_by BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    state      JSONB       NOT NULL,
    version    INTEGER     NOT NULL DEFAULT 0,
    turn_ends_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- One open game per room
CREATE UNIQUE INDEX uq_room_games_open ON room_games (room_id) WHERE status IN ('WAITING', 'PLAYING');
CREATE INDEX idx_room_games_due ON room_games (turn_ends_at) WHERE status = 'PLAYING';
