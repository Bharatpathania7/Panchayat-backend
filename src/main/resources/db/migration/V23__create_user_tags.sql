-- BFF tags: a relationship between two people, formed by winning a BFF
-- Kharidari. Every coin gifted between the two adds one intimacy point.
CREATE TABLE user_tags (
    id BIGSERIAL PRIMARY KEY,
    -- The auction winner and the person who sat on the auction seat.
    user_a BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_b BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- The same two people can only have one tag (in either order).
    pair_low BIGINT NOT NULL,
    pair_high BIGINT NOT NULL,
    relation VARCHAR(20) NOT NULL,
    token VARCHAR(20) NOT NULL,
    intimacy BIGINT NOT NULL DEFAULT 0,
    room_name VARCHAR(100),
    declaration VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_tags_pair UNIQUE (pair_low, pair_high),
    CONSTRAINT chk_user_tags_two_people CHECK (user_a <> user_b),
    CONSTRAINT chk_user_tags_relation CHECK (relation IN ('brosis', 'besties', 'bro', 'confidant', 'apprentice', 'sibling'))
);

CREATE INDEX idx_user_tags_a ON user_tags(user_a);
CREATE INDEX idx_user_tags_b ON user_tags(user_b);
