-- One person with many fake accounts from the same connection: remember where an account signed up
-- and let the admin panel cap the welcome red packet per network.
ALTER TABLE users ADD COLUMN IF NOT EXISTS signup_ip VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_users_signup_ip ON users (signup_ip) WHERE signup_ip IS NOT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS referral_note VARCHAR(120);

-- 0 = no limit. 1 = only the first account from a network gets the welcome red packet.
ALTER TABLE referral_settings ADD COLUMN IF NOT EXISTS max_welcome_per_network INT NOT NULL DEFAULT 1 CHECK (max_welcome_per_network >= 0);
-- Gifts between two accounts from the same network are refused (CP partners are always allowed).
ALTER TABLE referral_settings ADD COLUMN IF NOT EXISTS block_same_network_gifts BOOLEAN NOT NULL DEFAULT TRUE;
