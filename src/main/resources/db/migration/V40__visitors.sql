-- Profile visitors: every visit (at most one per visitor per 10 minutes)
CREATE TABLE profile_visits (
    id         BIGSERIAL PRIMARY KEY,
    owner_id   BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    visitor_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    visited_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (owner_id <> visitor_id)
);
CREATE INDEX idx_profile_visits_owner ON profile_visits (owner_id, visited_at DESC);
CREATE INDEX idx_profile_visits_visitor ON profile_visits (visitor_id, visited_at DESC);

-- Visitor log: who visited you is shown while it's on (bought with coins)
ALTER TABLE users ADD COLUMN IF NOT EXISTS visitor_log_until TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS visitors_seen_at TIMESTAMPTZ;

-- Plans, priced in coins, set from the admin panel
CREATE TABLE visitor_log_plans (
    id         BIGSERIAL PRIMARY KEY,
    label      VARCHAR(40)  NOT NULL,
    days       INTEGER      NOT NULL CHECK (days > 0 AND days <= 3650),
    price      BIGINT       NOT NULL CHECK (price > 0),
    badge      VARCHAR(20),
    sort_order INTEGER      NOT NULL DEFAULT 0,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
INSERT INTO visitor_log_plans (label, days, price, badge, sort_order) VALUES
    ('2 days', 2, 60, NULL, 1),
    ('7 days', 7, 200, 'POPULAR', 2),
    ('1 month', 30, 1200, 'BEST VALUE', 3);
