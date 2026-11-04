-- Sarpanch: Panchayat's official account (a real user row), its messages in
-- the normal chat tables, broadcasts and editable automatic texts.

ALTER TABLE users ADD COLUMN IF NOT EXISTS official BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_one_official ON users (official) WHERE official;

-- A special short ID for the official account (shown and searchable), set from the admin panel
ALTER TABLE users ADD COLUMN IF NOT EXISTS display_public_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_display_public_id ON users (display_public_id) WHERE display_public_id IS NOT NULL;

INSERT INTO users (email, full_name, bio, email_verified, profile_completed, official)
SELECT 'sarpanch@panchayat.system', 'Sarpanch', 'The official Panchayat account. News, events and help from the Panchayat team.', TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM users WHERE official);
UPDATE users SET display_public_id = 10000001 WHERE official AND display_public_id IS NULL;

-- One pinned, read-only conversation per user with Sarpanch
ALTER TABLE chat_conversations ADD COLUMN IF NOT EXISTS system_conversation BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_conversations ADD COLUMN IF NOT EXISTS pinned BOOLEAN NOT NULL DEFAULT FALSE;

-- SYSTEM messages and their extra fields
ALTER TABLE chat_messages DROP CONSTRAINT IF EXISTS chk_chat_messages_message_type;
ALTER TABLE chat_messages ADD CONSTRAINT chk_chat_messages_message_type CHECK (message_type IN ('TEXT', 'IMAGE', 'SYSTEM'));
ALTER TABLE chat_messages DROP CONSTRAINT IF EXISTS chk_chat_messages_image_metadata;
ALTER TABLE chat_messages ADD CONSTRAINT chk_chat_messages_image_metadata CHECK (
    (message_type IN ('TEXT', 'SYSTEM') AND media_storage_key IS NULL AND media_content_type IS NULL AND media_size_bytes IS NULL)
    OR (message_type = 'IMAGE' AND media_storage_key IS NOT NULL AND media_content_type IN ('image/jpeg', 'image/png') AND media_size_bytes IS NOT NULL AND media_size_bytes > 0));
ALTER TABLE chat_messages DROP CONSTRAINT IF EXISTS chk_chat_messages_content_by_type;
ALTER TABLE chat_messages ADD CONSTRAINT chk_chat_messages_content_by_type CHECK (length(ciphertext) > 0);
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS title VARCHAR(120);
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS image_url VARCHAR(500);
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS action_label VARCHAR(40);
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS action_url VARCHAR(300);
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS event_key VARCHAR(160);
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS category VARCHAR(20);
-- An automatic message is sent at most once per event (PayU retries, duplicate webhooks)
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_messages_event_key ON chat_messages (event_key) WHERE event_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_chat_messages_system ON chat_messages (conversation_id, created_at DESC) WHERE message_type = 'SYSTEM';

CREATE TABLE sarpanch_broadcasts (
    id            BIGSERIAL PRIMARY KEY,
    title         VARCHAR(120),
    body          VARCHAR(1000) NOT NULL,
    image_url     VARCHAR(500),
    action_label  VARCHAR(40),
    action_url    VARCHAR(300),
    category      VARCHAR(20)   NOT NULL DEFAULT 'ANNOUNCEMENT',
    audience      TEXT          NOT NULL,
    scheduled_at  TIMESTAMPTZ,
    status        VARCHAR(12)   NOT NULL DEFAULT 'SCHEDULED', -- DRAFT SCHEDULED SENDING SENT CANCELLED
    created_by    VARCHAR(80),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    sent_count    INTEGER       NOT NULL DEFAULT 0,
    last_user_id  BIGINT        NOT NULL DEFAULT 0,
    finished_at   TIMESTAMPTZ
);
CREATE INDEX idx_sarpanch_broadcasts_due ON sarpanch_broadcasts (status, scheduled_at);

-- Automatic texts, editable from the admin panel. {placeholders} are filled in when sent.
CREATE TABLE sarpanch_templates (
    key          VARCHAR(40) PRIMARY KEY,
    title        VARCHAR(120),
    body         VARCHAR(1000) NOT NULL,
    action_label VARCHAR(40),
    action_url   VARCHAR(300),
    category     VARCHAR(20) NOT NULL,
    updated_by   VARCHAR(80),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO sarpanch_templates (key, title, body, action_label, action_url, category) VALUES
 ('COINS_BOUGHT', 'Payment successful', 'Payment successful ✅ ₹{amount} paid. {coins} coins have been added to your wallet. Order {orderRef}.', 'Open wallet', '/wallet', 'PAYMENT'),
 ('PAYMENT_FAILED', 'Payment didn''t go through', 'Your payment of ₹{amount} didn''t go through. No money was taken, or it will be refunded by your bank. Order {orderRef}.', 'Try again', '/wallet', 'PAYMENT'),
 ('RED_PACKET_BOUGHT', 'Red packet ready', 'Your {packetName} ({coins} coins) is in your Bag 🧧. Drop it in any room.', 'Open Bag', '/bag', 'RED_PACKET'),
 ('VIP_ACTIVATED', 'VIP active', 'Welcome to VIP {level} 👑 Your VIP is active until {date}.', 'VIP benefits', '/vip', 'VIP'),
 ('VIP_EXPIRING', 'VIP ending soon', 'Your VIP ends on {date}. Renew to keep your frame, badge and entry.', 'Renew VIP', '/vip', 'VIP'),
 ('VIP_LEVEL_UP', 'VIP level up', 'You reached VIP {level} 🎉 Your new rewards are in your Bag.', 'Wear rewards', '/bag', 'VIP'),
 ('CP_ACCEPTED', 'New CP', '{name} accepted your proposal 💞', 'Open CP', '/cp', 'CP'),
 ('CP_ENDED', 'CP ended', 'Your CP with {name} has ended.', NULL, NULL, 'CP'),
 ('WARNING', 'Account warning', 'Your account got a warning for {reason}.', NULL, NULL, 'SAFETY'),
 ('WELCOME', 'Welcome', 'Namaste {name}! Welcome to Panchayat 🙏', 'Explore rooms', '/rooms', 'OTHER');
