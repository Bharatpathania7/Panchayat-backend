-- One account per phone: the (hashed) id of the phone an account was opened on.
ALTER TABLE users ADD COLUMN IF NOT EXISTS signup_device VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_users_signup_device ON users (signup_device) WHERE signup_device IS NOT NULL;

-- The admin panel can switch the rule off.
ALTER TABLE referral_settings ADD COLUMN IF NOT EXISTS one_account_per_device BOOLEAN NOT NULL DEFAULT TRUE;
