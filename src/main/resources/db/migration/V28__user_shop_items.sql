-- Shop items people bought with coins (rings, chat bubbles, red packets…): how many each person owns.
CREATE TABLE user_shop_items (
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    product_id      BIGINT      NOT NULL REFERENCES shop_products (id) ON DELETE CASCADE,
    quantity        INT         NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    first_bought_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_bought_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, product_id)
);
CREATE INDEX idx_user_shop_items_user ON user_shop_items (user_id, last_bought_at DESC);
