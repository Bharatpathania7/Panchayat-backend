-- Daily red packet from Sarpanch and the locked prize (7 active days). Everything is set, and switched on, from the admin panel.
CREATE TABLE daily_gift_settings (
    id                   INTEGER PRIMARY KEY CHECK (id = 1),
    enabled              BOOLEAN     NOT NULL DEFAULT FALSE,
    daily_coins          BIGINT      NOT NULL DEFAULT 1000,
    bonus_enabled        BOOLEAN     NOT NULL DEFAULT FALSE,
    bonus_coins          BIGINT      NOT NULL DEFAULT 50000,
    bonus_days           INTEGER     NOT NULL DEFAULT 7,
    bonus_active_minutes INTEGER     NOT NULL DEFAULT 120,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO daily_gift_settings (id) VALUES (1);

CREATE TABLE daily_gift_claims (
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day        DATE        NOT NULL,
    coins      BIGINT      NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, day)
);

-- How long a person was in the app each day (a phone pings once a minute while the app is open)
CREATE TABLE daily_activity (
    user_id   BIGINT  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day       DATE    NOT NULL,
    seconds   INTEGER NOT NULL DEFAULT 0,
    counted   BOOLEAN NOT NULL DEFAULT FALSE,
    last_ping TIMESTAMPTZ,
    PRIMARY KEY (user_id, day)
);
CREATE INDEX idx_daily_activity_day ON daily_activity (day);

CREATE TABLE locked_prize (
    user_id     BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    days_done   INTEGER     NOT NULL DEFAULT 0,
    coins       BIGINT,
    unlocked_at TIMESTAMPTZ,
    claimed_at  TIMESTAMPTZ,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
