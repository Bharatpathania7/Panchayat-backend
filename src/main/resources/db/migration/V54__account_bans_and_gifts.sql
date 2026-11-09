-- Account bans: permanent, or until a date (a suspension). Checked on every sign-in and request.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS ban_permanent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS banned_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS ban_reason VARCHAR(300),
    ADD COLUMN IF NOT EXISTS banned_at TIMESTAMPTZ;

-- Every ban, suspension and unban, with who did it.
CREATE TABLE IF NOT EXISTS account_ban_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action VARCHAR(12) NOT NULL,
    days INT,
    reason VARCHAR(300),
    admin_name VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_account_ban_log_user ON account_ban_log(user_id, created_at DESC);

-- Gifts staff sent to everyone at once (coins or a red packet).
CREATE TABLE IF NOT EXISTS admin_gift_all (
    id BIGSERIAL PRIMARY KEY,
    kind VARCHAR(12) NOT NULL,
    coins BIGINT,
    product_id BIGINT,
    quantity BIGINT,
    reason VARCHAR(200),
    admin_name VARCHAR(120),
    recipients INT NOT NULL DEFAULT 0,
    status VARCHAR(12) NOT NULL DEFAULT 'RUNNING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
