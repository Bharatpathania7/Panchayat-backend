-- What the Mukhiya pays (coins, set in the admin panel) to change the Parivar's name, title (sign) or logo. 0 = free.
ALTER TABLE parivar_settings ADD COLUMN IF NOT EXISTS change_name_price BIGINT NOT NULL DEFAULT 5000 CHECK (change_name_price >= 0);
ALTER TABLE parivar_settings ADD COLUMN IF NOT EXISTS change_sign_price BIGINT NOT NULL DEFAULT 5000 CHECK (change_sign_price >= 0);
ALTER TABLE parivar_settings ADD COLUMN IF NOT EXISTS change_logo_price BIGINT NOT NULL DEFAULT 5000 CHECK (change_logo_price >= 0);
