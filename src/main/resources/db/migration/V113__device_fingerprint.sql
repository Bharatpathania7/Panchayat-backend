-- A browser fingerprint (hashed), used together with the network to spot the same phone in a private window.
ALTER TABLE users ADD COLUMN IF NOT EXISTS signup_fp VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_users_signup_fp ON users (signup_fp) WHERE signup_fp IS NOT NULL;
