-- The Android app: push notifications, Google Play Billing, and deleting your own account.

-- Phones that get push notifications (Expo push tokens). One row per token.
CREATE TABLE push_devices (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token      VARCHAR(200) NOT NULL UNIQUE,
    platform   VARCHAR(12)  NOT NULL DEFAULT 'android',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_push_devices_user ON push_devices (user_id);

-- Which Google Play product (set up in the Play Console) is which coin pack / VIP plan / red packet. Set in the admin panel.
CREATE TABLE play_products (
    purpose         VARCHAR(12)  NOT NULL CHECK (purpose IN ('COIN_PACK', 'VIP', 'RED_PACKET')),
    product_id      BIGINT       NOT NULL,
    play_product_id VARCHAR(100) NOT NULL UNIQUE,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (purpose, product_id)
);

-- Every Google Play purchase we credited. The purchase token is unique, so one purchase can never be credited twice.
CREATE TABLE google_play_purchases (
    id              BIGSERIAL PRIMARY KEY,
    purchase_token  VARCHAR(400) NOT NULL UNIQUE,
    order_id        VARCHAR(100),
    user_id         BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose         VARCHAR(12)  NOT NULL,
    product_id      BIGINT       NOT NULL,
    play_product_id VARCHAR(100) NOT NULL,
    txn_id          VARCHAR(40),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_gp_purchases_user ON google_play_purchases (user_id, created_at DESC);
