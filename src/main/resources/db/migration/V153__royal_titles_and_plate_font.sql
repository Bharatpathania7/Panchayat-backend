-- The font for a name frame's plate (the same choices as the profile's "Name font": default, pacifico, dancing,
-- cinzel, yatra, righteous). Null = the frame's usual font.
ALTER TABLE user_rewards ADD COLUMN IF NOT EXISTS plate_font VARCHAR(16);
ALTER TABLE manual_topups ADD COLUMN IF NOT EXISTS plate_font VARCHAR(16);

-- The Royal Pack gives the titles with the uploaded artwork: 27 = Royal Nawab, 23 = Panchayat King, 25 = Panchayat Queen
-- (only where those titles exist, so another database is left as it is).
UPDATE royal_pack_settings SET nawab_title_id = 27 WHERE id = 1 AND EXISTS (SELECT 1 FROM profile_titles WHERE id = 27);
UPDATE royal_pack_settings SET king_title_id = 23 WHERE id = 1 AND EXISTS (SELECT 1 FROM profile_titles WHERE id = 23);
UPDATE royal_pack_settings SET queen_title_id = 25 WHERE id = 1 AND EXISTS (SELECT 1 FROM profile_titles WHERE id = 25);
