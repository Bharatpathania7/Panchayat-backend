-- A title for each coin pack: whoever buys the pack (payment gateway or manual UPI) gets it. days = 0 uses the title's own validity.
CREATE TABLE IF NOT EXISTS coin_pack_titles (
    pack_id  BIGINT PRIMARY KEY REFERENCES coin_packs (id) ON DELETE CASCADE,
    title_id BIGINT NOT NULL REFERENCES profile_titles (id) ON DELETE CASCADE,
    days     INT    NOT NULL DEFAULT 0 CHECK (days >= 0)
);
