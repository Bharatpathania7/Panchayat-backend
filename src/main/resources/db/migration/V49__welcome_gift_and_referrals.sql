-- New users: Sarpanch sends a 50,000-coin red packet to open (once).
ALTER TABLE users ADD COLUMN IF NOT EXISTS welcome_gift_offered    BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS welcome_gift_claimed_at TIMESTAMPTZ;

-- Referrals: who invited this person (their link), rewarded once.
ALTER TABLE users ADD COLUMN IF NOT EXISTS referred_by BIGINT REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS referred_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_users_referred_by ON users (referred_by) WHERE referred_by IS NOT NULL;

INSERT INTO sarpanch_templates (key, title, body, action_label, action_url, category) VALUES
 ('WELCOME_GIFT', 'A red packet for you 🧧', 'Namaste {name}! Welcome to Panchayat 🙏 Sarpanch has sent you a red packet of 50,000 coins. Tap to open it! Refer your friends and get 20,000 coins for each one who joins.', 'Open red packet', '/welcome-gift', 'REWARD'),
 ('REFERRAL_JOINED', 'Your friend joined! 🎉', '{name} joined Panchayat with your link. 20,000 coins have been added to your wallet.', 'Refer more friends', '/refer', 'REWARD')
ON CONFLICT (key) DO NOTHING;

-- Sarpanch tells everyone about referrals (sent in batches by the broadcast worker)
INSERT INTO sarpanch_broadcasts (title, body, action_label, action_url, category, audience, status, created_by)
VALUES ('Refer & earn 20,000 coins 🎁',
        'Invite your friends to Panchayat with your link or on WhatsApp. For every friend who joins, you get 20,000 coins. No limit!',
        'Refer now', '/refer', 'REWARD', '{"type":"ALL"}', 'SCHEDULED', 'system');
