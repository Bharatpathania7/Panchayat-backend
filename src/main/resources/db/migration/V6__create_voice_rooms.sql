CREATE TABLE voice_rooms (
    id BIGSERIAL PRIMARY KEY,

    owner_id BIGINT NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,

    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),

    room_type VARCHAR(20) NOT NULL DEFAULT 'VOICE',
    is_private BOOLEAN NOT NULL DEFAULT FALSE,
    max_participants INTEGER NOT NULL DEFAULT 8,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_voice_rooms_type
        CHECK (room_type IN ('VOICE', 'WATCH', 'KHARIDARI')),

    CONSTRAINT chk_voice_rooms_status
        CHECK (status IN ('ACTIVE', 'CLOSED')),

    CONSTRAINT chk_voice_rooms_max_participants
        CHECK (max_participants BETWEEN 2 AND 50)
);

CREATE INDEX idx_voice_rooms_status_created
    ON voice_rooms(status, created_at DESC);

CREATE INDEX idx_voice_rooms_owner
    ON voice_rooms(owner_id);
