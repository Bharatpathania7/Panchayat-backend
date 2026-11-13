-- Profile titles: 0 = a person can wear any number of titles (the new default); the admin can still set a limit.
ALTER TABLE title_settings DROP CONSTRAINT IF EXISTS title_settings_max_worn_check;
ALTER TABLE title_settings ADD CONSTRAINT title_settings_max_worn_check CHECK (max_worn BETWEEN 0 AND 100);
ALTER TABLE title_settings ALTER COLUMN max_worn SET DEFAULT 0;
UPDATE title_settings SET max_worn = 0 WHERE id = 1;
