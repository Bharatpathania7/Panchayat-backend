-- Phone bans. An admin can ban a person's phone(s) for good, on top of banning the account: the phone can no longer
-- sign in or open a new account, even with another email or Google account.

-- Every phone an account has signed in from (the signup phone alone misses a person who later uses a second phone)
CREATE TABLE IF NOT EXISTS user_devices (
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_hash VARCHAR(64) NOT NULL,
    first_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_hash)
);
INSERT INTO user_devices (user_id, device_hash)
SELECT id, signup_device FROM users WHERE signup_device IS NOT NULL
ON CONFLICT DO NOTHING;

-- No foreign key on user_id: a phone ban outlives the account (a banned person deleting their account must not free the phone)
CREATE TABLE IF NOT EXISTS banned_devices (
    id          BIGSERIAL PRIMARY KEY,
    device_hash VARCHAR(64),
    fp          VARCHAR(64),
    ip          VARCHAR(64),
    user_id     BIGINT,
    reason      VARCHAR(300),
    banned_by   VARCHAR(100),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_banned_devices_hash ON banned_devices (device_hash) WHERE device_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_banned_devices_user ON banned_devices (user_id);
CREATE INDEX IF NOT EXISTS idx_banned_devices_fp ON banned_devices (fp, ip) WHERE fp IS NOT NULL;
