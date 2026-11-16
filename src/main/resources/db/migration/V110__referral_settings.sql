-- Refer & earn rates, set from the admin panel (Referrals page).
CREATE TABLE IF NOT EXISTS referral_settings (
    id             INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    inviter_reward BIGINT NOT NULL DEFAULT 20000 CHECK (inviter_reward >= 0),
    welcome_gift   BIGINT NOT NULL DEFAULT 50000 CHECK (welcome_gift >= 0)
);
INSERT INTO referral_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- What the inviter was paid for each friend, so changing the rate later keeps the history right.
ALTER TABLE users ADD COLUMN IF NOT EXISTS referral_reward BIGINT;
UPDATE users SET referral_reward = 20000 WHERE referred_by IS NOT NULL AND referral_reward IS NULL;

-- The Sarpanch messages say the amounts, so they read them from the settings (only where nobody edited the text).
UPDATE sarpanch_templates SET body = 'Namaste {name}! Welcome to Panchayat 🙏 Sarpanch has sent you a red packet of {gift} coins. Tap to open it! Refer your friends and get {reward} coins for each one who joins.'
 WHERE key = 'WELCOME_GIFT' AND body = 'Namaste {name}! Welcome to Panchayat 🙏 Sarpanch has sent you a red packet of 50,000 coins. Tap to open it! Refer your friends and get 20,000 coins for each one who joins.';
UPDATE sarpanch_templates SET body = '{name} joined Panchayat with your link. {reward} coins have been added to your wallet.'
 WHERE key = 'REFERRAL_JOINED' AND body = '{name} joined Panchayat with your link. 20,000 coins have been added to your wallet.';
