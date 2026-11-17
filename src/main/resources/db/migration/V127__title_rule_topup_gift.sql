-- Title rules by money: top-ups (rupees) and gifts (coins), so a title is given automatically when a person reaches the amount.
ALTER TABLE title_auto_rules DROP CONSTRAINT IF EXISTS title_auto_rules_kind_check;
ALTER TABLE title_auto_rules ADD CONSTRAINT title_auto_rules_kind_check CHECK (kind IN ('IDS', 'VIP_LEVEL', 'PARIVAR_ROLE', 'TOPUP_TOTAL', 'TOPUP_ONE', 'GIFT_SENT', 'GIFT_RECEIVED'));
CREATE INDEX IF NOT EXISTS idx_room_gift_events_sender ON room_gift_events (sender_id);
CREATE INDEX IF NOT EXISTS idx_room_gift_events_receiver ON room_gift_events (receiver_id);
CREATE INDEX IF NOT EXISTS idx_payment_tx_user_status ON payment_transactions (user_id, status);
CREATE INDEX IF NOT EXISTS idx_manual_topups_user_status ON manual_topups (user_id, status);
