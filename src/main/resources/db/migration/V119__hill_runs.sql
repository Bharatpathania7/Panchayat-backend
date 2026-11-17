-- Pahadi Daud (solo hill-climb game): each run, and the limits the admin sets.
CREATE TABLE hill_settings (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    daily_cap   BIGINT  NOT NULL DEFAULT 5000,   -- most coins one person can take home in a day
    run_cap     BIGINT  NOT NULL DEFAULT 1500,   -- most coins from one run
    coin_big    INTEGER NOT NULL DEFAULT 10,     -- the coins on the road go 10, 5, 10, 5 ...
    coin_small  INTEGER NOT NULL DEFAULT 5,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO hill_settings (id) VALUES (1);

CREATE TABLE hill_runs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    distance    INTEGER     NOT NULL DEFAULT 0,
    coins       BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_hill_runs_user ON hill_runs (user_id, started_at);
