-- Chat moderation: an admin-managed list of abusive/NSFW words, and a log of chat messages that used one.
-- Messages are never blocked; they only get flagged here for a human to review.

CREATE TABLE moderation_banned_words (
    id         BIGSERIAL PRIMARY KEY,
    word       VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE moderation_violations (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source       VARCHAR(20)  NOT NULL, -- PRIVATE_CHAT | ROOM_CHAT
    room_id      BIGINT,                -- set for ROOM_CHAT
    matched_word VARCHAR(100) NOT NULL,
    snippet      VARCHAR(300) NOT NULL, -- the flagged text, for review context
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed     BOOLEAN      NOT NULL DEFAULT false
);
CREATE INDEX idx_moderation_violations_created ON moderation_violations (created_at DESC);
CREATE INDEX idx_moderation_violations_user ON moderation_violations (user_id);
