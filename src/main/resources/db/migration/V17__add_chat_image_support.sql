-- Add message type to distinguish text and image messages.
ALTER TABLE chat_messages
ADD COLUMN message_type VARCHAR(20) NOT NULL DEFAULT 'TEXT';

-- Restrict message types to supported values.
ALTER TABLE chat_messages
ADD CONSTRAINT chk_chat_messages_message_type
CHECK (message_type IN ('TEXT', 'IMAGE'));

-- Store metadata for encrypted image attachments.
ALTER TABLE chat_messages
ADD COLUMN media_storage_key VARCHAR(500);

ALTER TABLE chat_messages
ADD COLUMN media_content_type VARCHAR(100);

ALTER TABLE chat_messages
ADD COLUMN media_size_bytes BIGINT;

-- Validate image metadata when present.
ALTER TABLE chat_messages
ADD CONSTRAINT chk_chat_messages_media_size
CHECK (media_size_bytes IS NULL OR media_size_bytes > 0);

ALTER TABLE chat_messages
ADD CONSTRAINT chk_chat_messages_image_metadata
CHECK (
    (message_type = 'TEXT'
        AND media_storage_key IS NULL
        AND media_content_type IS NULL
        AND media_size_bytes IS NULL)
    OR
    (message_type = 'IMAGE'
        AND media_storage_key IS NOT NULL
        AND media_content_type IN ('image/jpeg', 'image/png')
        AND media_size_bytes IS NOT NULL
        AND media_size_bytes > 0)
);

-- Enforce the message type and content requirements.
ALTER TABLE chat_messages
ADD CONSTRAINT chk_chat_messages_content_by_type
CHECK (
    (message_type = 'TEXT' AND length(ciphertext) > 0)
    OR
    (message_type = 'IMAGE' AND length(ciphertext) > 0)
);