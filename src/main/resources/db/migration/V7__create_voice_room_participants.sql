CREATE TABLE voice_room_participants (
    id BIGSERIAL PRIMARY KEY,

    room_id BIGINT NOT NULL
        REFERENCES voice_rooms(id) ON DELETE CASCADE,

    user_id BIGINT NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,

    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',

    status VARCHAR(20) NOT NULL DEFAULT 'JOINED',

    is_muted BOOLEAN NOT NULL DEFAULT FALSE,

    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    left_at TIMESTAMPTZ,

    CONSTRAINT chk_voice_room_participant_role
        CHECK (role IN ('OWNER', 'MODERATOR', 'MEMBER')),

    CONSTRAINT chk_voice_room_participant_status
        CHECK (status IN ('JOINED', 'LEFT'))
);

CREATE INDEX idx_voice_room_participants_room_status
    ON voice_room_participants(room_id, status);

CREATE INDEX idx_voice_room_participants_user
    ON voice_room_participants(user_id);

CREATE UNIQUE INDEX uq_voice_room_active_participant
    ON voice_room_participants(room_id, user_id)
    WHERE status = 'JOINED';