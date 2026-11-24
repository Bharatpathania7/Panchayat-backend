-- Royal Pack: one special pack, sold ONLY through Munshi ji (manual UPI top-up), never through the payment gateway.
-- Whoever buys it gets: the Royal frame with a name they choose (up to 14 letters) on its plate, the pack's coins
-- (10 crore to start), the "Royal Nawab" title, and "Panchayat King" (male) or "Panchayat Queen" (female).
-- The price, coins, the frame, the titles and how many days the frame / titles last are all set in the admin panel.

-- How big a name frame is drawn around a seat / profile picture (frame width / spot width). VIP frames use 1.45.
ALTER TABLE name_frames ADD COLUMN IF NOT EXISTS display_size NUMERIC(4,2) NOT NULL DEFAULT 1.45 CHECK (display_size BETWEEN 1 AND 3);

-- The name a person chose for their own name frame's plate (null = their account name)
ALTER TABLE user_rewards ADD COLUMN IF NOT EXISTS plate_name VARCHAR(14);

-- A Munshi ji request is a coin pack (as before) or the Royal Pack
ALTER TABLE manual_topups ADD COLUMN IF NOT EXISTS kind VARCHAR(12) NOT NULL DEFAULT 'COIN_PACK';
ALTER TABLE manual_topups ADD COLUMN IF NOT EXISTS plate_name VARCHAR(14);
-- KING or QUEEN: which of the two titles this Royal Pack gives (from the buyer's gender, or their choice when it isn't set)
ALTER TABLE manual_topups ADD COLUMN IF NOT EXISTS royal_title VARCHAR(6);

-- The Royal frame (the artwork ships with the website)
INSERT INTO name_frames (name, image_url, frame_scale, frame_y, plate_x, plate_y, plate_w, plate_h, text_color, stroke_color, display_size, sort_order)
VALUES ('Royal frame', '/frames/royal-name.webp', 2.100, 0.480, 31.0, 82.4, 38.0, 11.0, '#fff1b8', '#3b0d5c', 1.70,
        (SELECT coalesce(max(sort_order), 0) + 1 FROM name_frames));

INSERT INTO profile_titles (name, color_from, color_to, text_color, rarity, price_coins, free, for_sale, duration_days, sort_order) VALUES
    ('Royal Nawab',     '#6a1fb8', '#f0b232', '#fff7dc', 'ULTRA_RARE', 0, FALSE, FALSE, 30, 900),
    ('Panchayat King',  '#b8860b', '#ffd56a', '#2a1600', 'ULTRA_RARE', 0, FALSE, FALSE, 30, 901),
    ('Panchayat Queen', '#c2185b', '#b06bff', '#ffffff', 'ULTRA_RARE', 0, FALSE, FALSE, 30, 902);

CREATE TABLE royal_pack_settings (
    id             INT PRIMARY KEY CHECK (id = 1),
    enabled        BOOLEAN       NOT NULL DEFAULT TRUE,
    name           VARCHAR(40)   NOT NULL DEFAULT 'Royal Nawab Pack',
    price_inr      NUMERIC(10,2) NOT NULL DEFAULT 9999 CHECK (price_inr >= 1),
    coins          BIGINT        NOT NULL DEFAULT 100000000 CHECK (coins >= 0),
    frame_id       BIGINT REFERENCES name_frames (id) ON DELETE SET NULL,
    frame_days     INT           NOT NULL DEFAULT 30 CHECK (frame_days >= 0),
    title_days     INT           NOT NULL DEFAULT 30 CHECK (title_days >= 0),
    nawab_title_id BIGINT REFERENCES profile_titles (id) ON DELETE SET NULL,
    king_title_id  BIGINT REFERENCES profile_titles (id) ON DELETE SET NULL,
    queen_title_id BIGINT REFERENCES profile_titles (id) ON DELETE SET NULL,
    badge          VARCHAR(24)   NOT NULL DEFAULT 'ROYAL OFFER',
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

INSERT INTO royal_pack_settings (id, frame_id, nawab_title_id, king_title_id, queen_title_id)
SELECT 1,
       (SELECT id FROM name_frames WHERE name = 'Royal frame' ORDER BY id DESC LIMIT 1),
       (SELECT id FROM profile_titles WHERE name = 'Royal Nawab' ORDER BY id DESC LIMIT 1),
       (SELECT id FROM profile_titles WHERE name = 'Panchayat King' ORDER BY id DESC LIMIT 1),
       (SELECT id FROM profile_titles WHERE name = 'Panchayat Queen' ORDER BY id DESC LIMIT 1);
