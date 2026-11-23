-- A co-owner: set by the real owner once the room reaches level 5. Same power as the owner
-- everywhere except the fixed owner seat (seat 0) and appointing/removing a co-owner itself.
CREATE TABLE voice_room_co_owners (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES voice_rooms(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_by BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_voice_room_co_owners UNIQUE (room_id, user_id)
);

CREATE INDEX idx_voice_room_co_owners_room ON voice_room_co_owners(room_id);

ALTER TABLE voice_room_participants DROP CONSTRAINT chk_voice_room_participant_role;
ALTER TABLE voice_room_participants ADD CONSTRAINT chk_voice_room_participant_role
    CHECK (role IN ('OWNER', 'CO_OWNER', 'MODERATOR', 'MEMBER'));
