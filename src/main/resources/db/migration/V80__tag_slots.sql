-- BFF tag slots: how many tags a person can hold is set in the admin panel. Extra slots can be opened with coins.
-- A person can now hold several tags of the same type (each with a different person).
CREATE TABLE IF NOT EXISTS tag_settings (
    id          INT PRIMARY KEY CHECK (id = 1),
    base_slots  INT     NOT NULL DEFAULT 5  CHECK (base_slots >= 0),
    max_slots   INT     NOT NULL DEFAULT 12 CHECK (max_slots >= 1),
    slot_price  BIGINT  NOT NULL DEFAULT 5000 CHECK (slot_price >= 0),
    vip_bonus   BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO tag_settings (id) VALUES (1) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS user_tag_slots (
    user_id BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    bought  INT NOT NULL DEFAULT 0 CHECK (bought >= 0)
);
