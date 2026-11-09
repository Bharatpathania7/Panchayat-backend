-- Lucky coins: 98 in 100 gifts get a small amount (different each time), 2 in 100 a big one.
ALTER TABLE gift_settings ALTER COLUMN lucky_high_chance SET DEFAULT 2;
ALTER TABLE gift_settings ALTER COLUMN lucky_middle_chance SET DEFAULT 0;
UPDATE gift_settings SET lucky_high_chance = 2, lucky_middle_chance = 0 WHERE id = 1;
