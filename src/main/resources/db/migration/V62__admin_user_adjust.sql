-- Coins or Charm staff took away from a user (the coin side is also in coin_transactions).
CREATE TABLE IF NOT EXISTS admin_user_adjust (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       VARCHAR(8)  NOT NULL CHECK (kind IN ('COINS', 'CHARM')),
    amount     BIGINT      NOT NULL,
    reason     VARCHAR(300),
    admin_name VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_admin_user_adjust_user ON admin_user_adjust (user_id, created_at DESC);
