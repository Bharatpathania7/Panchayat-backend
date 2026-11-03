-- Group chats: a conversation is DIRECT (two people) or GROUP (a name, an owner, admins, members).
ALTER TABLE chat_conversations ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'DIRECT'
    CHECK (kind IN ('DIRECT', 'GROUP'));
ALTER TABLE chat_conversations ADD COLUMN name VARCHAR(60);
ALTER TABLE chat_conversations ADD COLUMN created_by BIGINT REFERENCES users (id) ON DELETE SET NULL;

-- Each member's role in a group, and whether they muted the conversation (no alerts for them)
ALTER TABLE chat_conversation_participants ADD COLUMN role VARCHAR(10) NOT NULL DEFAULT 'MEMBER'
    CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'));
ALTER TABLE chat_conversation_participants ADD COLUMN muted BOOLEAN NOT NULL DEFAULT FALSE;

-- Blocking: neither side can message the other in a direct chat while a block exists
CREATE TABLE user_blocks (
    blocker_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);
CREATE INDEX idx_user_blocks_blocked ON user_blocks (blocked_id);

-- When someone last had the app open (online status shows "last seen …")
ALTER TABLE users ADD COLUMN last_seen_at TIMESTAMPTZ;

-- Charm from gifts already received: 1 per 5 coins, double on Saturdays and Sundays (India time)
UPDATE users u SET charm_points = u.charm_points + c.charm
FROM (
    SELECT receiver_id,
           sum(floor(coins * (CASE WHEN extract(isodow FROM created_at AT TIME ZONE 'Asia/Kolkata') IN (6, 7) THEN 2 ELSE 1 END) / 5.0))::bigint AS charm
    FROM room_gift_events
    GROUP BY receiver_id
) c
WHERE c.receiver_id = u.id;
