-- Room admins chosen by the owner. Admins can moderate seats, the mic
-- queue, kick and ban. Being in the room as an admin also "opens" it.
CREATE TABLE voice_room_admins (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES voice_rooms(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_by BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_voice_room_admins UNIQUE (room_id, user_id)
);

CREATE INDEX idx_voice_room_admins_room ON voice_room_admins(room_id);

-- People blocked from a room. expires_at NULL = banned until unbanned;
-- a kick is a ban with a short expiry.
CREATE TABLE voice_room_bans (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES voice_rooms(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    banned_by BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_voice_room_bans UNIQUE (room_id, user_id)
);

CREATE INDEX idx_voice_room_bans_room ON voice_room_bans(room_id);

-- Seat settings the owner or an admin chose; they survive a server restart.
ALTER TABLE voice_rooms
    ADD COLUMN mic_order BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN closed_seats VARCHAR(100) NOT NULL DEFAULT '';
