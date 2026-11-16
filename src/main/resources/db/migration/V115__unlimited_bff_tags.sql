-- BFF tags: nobody runs out of slots. The admin panel can switch the slot limits back on (BFF tags card).
ALTER TABLE tag_settings ADD COLUMN IF NOT EXISTS unlimited BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE tag_settings SET unlimited = TRUE WHERE id = 1;
