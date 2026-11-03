-- Long-lived sign-in sessions. Only a SHA-256 hash of each refresh token is
-- stored; the token itself exists only on the user's device.
CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Set when used (rotated) or signed out. Using a revoked token again
    -- means it was copied: every session of that user is ended.
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
