-- Daily tasks, managed from the admin panel. Progress resets every day (India time).
CREATE TABLE daily_tasks (
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(120) NOT NULL,
    kind         VARCHAR(30)  NOT NULL CHECK (kind IN ('CHECK_IN', 'VOICE_MINUTES', 'SEND_GIFT', 'PLAY_GAME', 'INVITE_FRIEND', 'SEND_MESSAGE', 'POST_MOMENT')),
    goal         INT          NOT NULL CHECK (goal BETWEEN 1 AND 10000),
    reward_coins BIGINT       NOT NULL CHECK (reward_coins BETWEEN 0 AND 1000000),
    reward_exp   INT          NOT NULL DEFAULT 50 CHECK (reward_exp BETWEEN 0 AND 10000),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_daily_tasks (
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day        DATE   NOT NULL,
    task_id    BIGINT NOT NULL REFERENCES daily_tasks(id) ON DELETE CASCADE,
    progress   INT    NOT NULL DEFAULT 0,
    claimed_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, day, task_id)
);
CREATE INDEX idx_user_daily_tasks_day ON user_daily_tasks (day);

INSERT INTO daily_tasks (title, kind, goal, reward_coins, reward_exp, sort_order) VALUES
 ('Check in today', 'CHECK_IN', 1, 50, 50, 1),
 ('Spend 10 minutes in a voice room', 'VOICE_MINUTES', 10, 100, 50, 2),
 ('Send a gift', 'SEND_GIFT', 1, 150, 50, 3),
 ('Play one game', 'PLAY_GAME', 1, 120, 50, 4),
 ('Invite a friend to a room', 'INVITE_FRIEND', 1, 180, 50, 5);
