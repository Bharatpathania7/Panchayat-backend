-- =========================================================
-- V16: Chat system
-- Personal conversations, encrypted messages, room chat,
-- read receipts, and super-admin inspection audit.
-- =========================================================

-- 1. Personal conversations
CREATE TABLE chat_conversations (
    id BIGSERIAL PRIMARY KEY,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_chat_conversations_id UNIQUE (id)
);

-- 2. Conversation participants
CREATE TABLE chat_conversation_participants (
    id BIGSERIAL PRIMARY KEY,

    conversation_id BIGINT NOT NULL
        REFERENCES chat_conversations(id) ON DELETE CASCADE,

    user_id BIGINT NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,

    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_read_message_id BIGINT,

    CONSTRAINT uq_chat_conversation_user
        UNIQUE (conversation_id, user_id)
);

CREATE INDEX idx_chat_conversation_participants_user
    ON chat_conversation_participants(user_id, conversation_id);

-- 3. Encrypted chat messages
CREATE TABLE chat_messages (
    id BIGSERIAL PRIMARY KEY,

    conversation_id BIGINT NOT NULL
        REFERENCES chat_conversations(id) ON DELETE CASCADE,

    sender_user_id BIGINT NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,

    ciphertext TEXT NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    encryption_key_version INTEGER NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT chk_chat_message_key_version
        CHECK (encryption_key_version > 0),

    CONSTRAINT chk_chat_message_ciphertext
        CHECK (length(ciphertext) > 0),

    CONSTRAINT chk_chat_message_nonce
        CHECK (length(nonce) > 0)
);

CREATE INDEX idx_chat_messages_conversation_created
    ON chat_messages(conversation_id, created_at DESC, id DESC);

CREATE INDEX idx_chat_messages_sender
    ON chat_messages(sender_user_id, created_at DESC);

-- 4. Read receipts
CREATE TABLE chat_message_receipts (
    id BIGSERIAL PRIMARY KEY,

    message_id BIGINT NOT NULL
        REFERENCES chat_messages(id) ON DELETE CASCADE,

    user_id BIGINT NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,

    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,

    CONSTRAINT uq_chat_message_receipt_user
        UNIQUE (message_id, user_id)
);

CREATE INDEX idx_chat_receipts_user_unread
    ON chat_message_receipts(user_id, read_at);

-- 5. Voice-room chat messages
-- Access control must additionally verify ACTIVE room and
-- JOINED room membership in the application service.
CREATE TABLE voice_room_chat_messages (
    id BIGSERIAL PRIMARY KEY,

    room_id BIGINT NOT NULL
        REFERENCES voice_rooms(id) ON DELETE CASCADE,

    sender_user_id BIGINT NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,

    ciphertext TEXT NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    encryption_key_version INTEGER NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT chk_voice_chat_key_version
        CHECK (encryption_key_version > 0),

    CONSTRAINT chk_voice_chat_ciphertext
        CHECK (length(ciphertext) > 0),

    CONSTRAINT chk_voice_chat_nonce
        CHECK (length(nonce) > 0)
);

CREATE INDEX idx_voice_room_chat_messages_history
    ON voice_room_chat_messages(room_id, created_at DESC, id DESC);

CREATE INDEX idx_voice_room_chat_messages_sender
    ON voice_room_chat_messages(sender_user_id, created_at DESC);

-- 6. Voice-room read receipts
CREATE TABLE voice_room_chat_receipts (
    id BIGSERIAL PRIMARY KEY,

    message_id BIGINT NOT NULL
        REFERENCES voice_room_chat_messages(id) ON DELETE CASCADE,

    user_id BIGINT NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,

    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,

    CONSTRAINT uq_voice_room_chat_receipt_user
        UNIQUE (message_id, user_id)
);

CREATE INDEX idx_voice_room_chat_receipts_user_unread
    ON voice_room_chat_receipts(user_id, read_at);

-- 7. Auditable Super Admin inspection records
-- Never store plaintext messages, encryption keys, or JWTs here.
CREATE TABLE chat_inspection_audit (
    id BIGSERIAL PRIMARY KEY,

    admin_account_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL
        REFERENCES chat_conversations(id) ON DELETE RESTRICT,

    reason VARCHAR(500) NOT NULL,
    action VARCHAR(30) NOT NULL DEFAULT 'VIEW',

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_chat_inspection_action
        CHECK (action IN ('VIEW', 'EXPORT'))
);

CREATE INDEX idx_chat_inspection_audit_conversation
    ON chat_inspection_audit(conversation_id, created_at DESC);

CREATE INDEX idx_chat_inspection_audit_admin
    ON chat_inspection_audit(admin_account_id, created_at DESC);