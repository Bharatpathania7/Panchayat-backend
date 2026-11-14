-- Chat clean-up: delete a message for yourself, unsend your own message (deleted_at, for everyone), delete a chat or all chats for yourself.
-- A chat deleted for you hides everything up to cleared_at; a new message brings the chat back with only the new messages.
ALTER TABLE chat_conversation_participants ADD COLUMN IF NOT EXISTS cleared_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS chat_message_hidden (
    message_id BIGINT NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (message_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_chat_message_hidden_user ON chat_message_hidden (user_id);
