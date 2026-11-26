-- Duo Dash: the two-player co-op game. Results come from the game server (never from a phone), so the
-- couple ranking can be trusted. A "pair" is two users, stored with the smaller id first.

CREATE TABLE duo_pairs (
    pair_key       VARCHAR(41) PRIMARY KEY,
    user_a         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_b         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    best_level     INT         NOT NULL DEFAULT 0,
    levels_done    INT         NOT NULL DEFAULT 0,
    total_stars    INT         NOT NULL DEFAULT 0,
    total_coins    INT         NOT NULL DEFAULT 0,
    couple_moments INT         NOT NULL DEFAULT 0,
    plays          INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_duo_pairs_rank ON duo_pairs (best_level DESC, total_stars DESC, total_coins DESC);
CREATE INDEX idx_duo_pairs_a ON duo_pairs (user_a);
CREATE INDEX idx_duo_pairs_b ON duo_pairs (user_b);

-- the best a pair has done on each level
CREATE TABLE duo_level_best (
    pair_key     VARCHAR(41) NOT NULL REFERENCES duo_pairs (pair_key) ON DELETE CASCADE,
    level_no     INT         NOT NULL,
    stars        INT         NOT NULL,
    best_time_ms INT         NOT NULL,
    coins        INT         NOT NULL,
    PRIMARY KEY (pair_key, level_no)
);

-- every finished level
CREATE TABLE duo_results (
    id             BIGSERIAL PRIMARY KEY,
    pair_key       VARCHAR(41) NOT NULL REFERENCES duo_pairs (pair_key) ON DELETE CASCADE,
    level_id       VARCHAR(12) NOT NULL,
    level_no       INT         NOT NULL,
    stars          INT         NOT NULL,
    time_ms        INT         NOT NULL,
    coins          INT         NOT NULL,
    couple_moments INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_duo_results_time ON duo_results (created_at DESC);
CREATE INDEX idx_duo_results_pair ON duo_results (pair_key, created_at DESC);
