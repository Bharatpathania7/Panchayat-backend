-- Active Level: EXP from activity, with a daily cap per source (India time days).
ALTER TABLE users ADD COLUMN IF NOT EXISTS exp BIGINT NOT NULL DEFAULT 0;

CREATE TABLE user_exp_daily (
    user_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day     DATE        NOT NULL,
    source  VARCHAR(12) NOT NULL, -- GAME VOICE TASK FRIEND PURCHASE
    amount  INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day, source)
);
