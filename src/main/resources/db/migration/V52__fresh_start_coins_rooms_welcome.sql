-- Fresh start, part 2 (one time):
--  1. every room's level starts from 0 (room points)
--  2. everyone's gold coins go to 0 (recorded in the coin history as a "Fresh start" debit)
--  3. everyone already on Panchayat gets Sarpanch's joining red packet (50,000 coins to open)

-- 1. Room levels
UPDATE voice_rooms SET points = 0;

-- 2. Coins to 0, through the ledger so balances and history stay consistent
INSERT INTO coin_transactions (user_id, transaction_type, amount, balance_before, balance_after, reason, reference_id, description, created_at, idempotency_key)
SELECT w.user_id, 'DEBIT', w.balance, w.balance, 0, 'FRESH_START', 'fresh-start', 'Fresh start: coins reset to 0', now(), 'fresh-start:' || w.user_id
FROM coin_wallets w
WHERE w.balance > 0
ON CONFLICT DO NOTHING;
UPDATE coin_wallets SET balance = 0, updated_at = now() WHERE balance > 0;

-- 3. The joining red packet for everyone (opened once from the popup or Sarpanch's message)
UPDATE users SET welcome_gift_offered = TRUE, welcome_gift_claimed_at = NULL WHERE NOT coalesce(official, FALSE);

INSERT INTO sarpanch_broadcasts (title, body, action_label, action_url, category, audience, status, created_by)
VALUES ('A red packet for you 🧧',
        'Namaste! Panchayat has a fresh start 🎉 Sarpanch has sent everyone a red packet of 50,000 coins. Tap to open it! Everyone also has 3 days of free VIP.',
        'Open red packet', '/welcome-gift', 'REWARD', '{"type":"ALL"}', 'SCHEDULED', 'system');
