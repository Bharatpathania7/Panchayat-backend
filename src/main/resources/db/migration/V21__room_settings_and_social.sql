-- Room settings chosen by the owner or an admin, shared with everyone.
ALTER TABLE voice_rooms
    ADD COLUMN category VARCHAR(30),
    ADD COLUMN cover_theme VARCHAR(20),
    ADD COLUMN cover_motif VARCHAR(20),
    ADD COLUMN cover_image_url VARCHAR(1000),
    ADD COLUMN cover_image_public_id VARCHAR(300),
    ADD COLUMN announcement VARCHAR(500),
    -- 4-digit room password. Shown only to the owner and admins, checked on join.
    ADD COLUMN password VARCHAR(4),
    -- Display and chat settings (background, banText, noImages, ...), JSON object.
    ADD COLUMN settings TEXT NOT NULL DEFAULT '{}',
    ADD COLUMN member_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN fan_count INTEGER NOT NULL DEFAULT 0;

-- People who joined a room as members.
CREATE TABLE voice_room_members (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES voice_rooms(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_voice_room_members UNIQUE (room_id, user_id)
);
CREATE INDEX idx_voice_room_members_user ON voice_room_members(user_id);

-- People who follow a room (fans).
CREATE TABLE voice_room_followers (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES voice_rooms(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_voice_room_followers UNIQUE (room_id, user_id)
);
CREATE INDEX idx_voice_room_followers_user ON voice_room_followers(user_id);
