-- Gift categories are now managed in the admin panel (add your own).
-- kind: NORMAL = anyone can send; VIP = only people with active VIP can send; CP = only to your CP partner.
CREATE TABLE IF NOT EXISTS gift_categories (
    name       VARCHAR(30) PRIMARY KEY,
    kind       VARCHAR(8)  NOT NULL DEFAULT 'NORMAL' CHECK (kind IN ('NORMAL', 'VIP', 'CP')),
    sort_order INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_gift_categories_lower ON gift_categories (lower(name));

INSERT INTO gift_categories (name, kind, sort_order) VALUES
 ('Popular', 'NORMAL', 1), ('Cute', 'NORMAL', 2), ('Romantic', 'NORMAL', 3), ('Celebration', 'NORMAL', 4),
 ('Royal', 'NORMAL', 5), ('Legendary', 'NORMAL', 6), ('VIP', 'VIP', 7), ('CP', 'CP', 8)
ON CONFLICT DO NOTHING;
