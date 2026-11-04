-- Following people. Two people who follow each other are friends.
CREATE TABLE user_follows (
    follower_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    followee_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followee_id),
    CHECK (follower_id <> followee_id)
);
CREATE INDEX idx_user_follows_followee ON user_follows (followee_id);

-- Moments: posts with text, photos or a video
CREATE TABLE moments (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body          VARCHAR(1000),
    media         JSONB        NOT NULL DEFAULT '[]'::jsonb, -- [{url, type: IMAGE|VIDEO, publicId, format}]
    visibility    VARCHAR(10)  NOT NULL DEFAULT 'PUBLIC',     -- PUBLIC | FRIENDS
    like_count    INTEGER      NOT NULL DEFAULT 0,
    comment_count INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);
CREATE INDEX idx_moments_feed ON moments (created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_moments_user ON moments (user_id, created_at DESC) WHERE deleted_at IS NULL;

CREATE TABLE moment_likes (
    moment_id  BIGINT      NOT NULL REFERENCES moments (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (moment_id, user_id)
);

CREATE TABLE moment_comments (
    id         BIGSERIAL PRIMARY KEY,
    moment_id  BIGINT       NOT NULL REFERENCES moments (id) ON DELETE CASCADE,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reply_to   BIGINT       REFERENCES moment_comments (id) ON DELETE SET NULL,
    body       VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX idx_moment_comments_moment ON moment_comments (moment_id, created_at);
