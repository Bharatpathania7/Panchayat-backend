-- Gift switches set in the admin panel. Lucky coins (random coins to the receiver of a gift) are OFF by default.
CREATE TABLE IF NOT EXISTS gift_settings (
    id            INT PRIMARY KEY CHECK (id = 1),
    lucky_enabled BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO gift_settings (id, lucky_enabled) VALUES (1, FALSE) ON CONFLICT DO NOTHING;
