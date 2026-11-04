-- Message requests: a direct chat started by someone who isn't your friend
-- (mutual follow) lands in your Requests until you accept or reply.
-- ACCEPTED: a normal chat · PENDING: a request waiting for you · DECLINED: you deleted the request
ALTER TABLE chat_conversation_participants
    ADD COLUMN IF NOT EXISTS request_state VARCHAR(10) NOT NULL DEFAULT 'ACCEPTED';
