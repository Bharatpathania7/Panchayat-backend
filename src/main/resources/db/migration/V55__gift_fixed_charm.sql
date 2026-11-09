-- A fixed charm per gift, set in the admin panel. NULL = the old rule (1 charm per 5 coins).
ALTER TABLE gifts ADD COLUMN IF NOT EXISTS charm BIGINT CHECK (charm >= 0);
