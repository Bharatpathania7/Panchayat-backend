-- Server-wide gift notices only for gifts the admin picks (not for every big or x99 gift).
ALTER TABLE gifts ADD COLUMN IF NOT EXISTS announce BOOLEAN NOT NULL DEFAULT FALSE;
-- To start: the 3 most recently added gifts
UPDATE gifts SET announce = TRUE WHERE id IN (SELECT id FROM gifts WHERE active ORDER BY created_at DESC LIMIT 3);
