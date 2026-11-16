-- Removing a Guard between two people: by asking (the other accepts or refuses) or right away for coins.

-- The price of removing it right away, set in the admin panel.
CREATE TABLE IF NOT EXISTS guard_settings (
    id                INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    force_delete_cost BIGINT NOT NULL DEFAULT 1000000 CHECK (force_delete_cost >= 0)
);
INSERT INTO guard_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- A request to remove the Guard between two people. A request that was accepted (or an instant removal) leaves nothing
-- behind: its row is deleted together with the Guard. A refused one stays for a day so the same person can't keep asking.
CREATE TABLE IF NOT EXISTS guard_delete_requests (
    id           BIGSERIAL PRIMARY KEY,
    from_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    to_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status       VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'REFUSED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at TIMESTAMPTZ,
    CHECK (from_id <> to_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_guard_delete_pending ON guard_delete_requests (LEAST(from_id, to_id), GREATEST(from_id, to_id)) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_guard_delete_to ON guard_delete_requests (to_id);

-- Gifts stay in the gift history, rankings and the books; this only takes them out of the Guard count once removed.
ALTER TABLE room_gift_events ADD COLUMN IF NOT EXISTS guard_cleared BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO sarpanch_templates (key, title, body, action_label, action_url, category) VALUES
 ('GUARD_DELETE_REQUEST', 'Guard removal request', '{name} wants to remove the Guard between you two. Open Guard to accept or refuse. If you refuse, nothing changes.', 'Open Guard', '/guard', 'OTHER'),
 ('GUARD_DELETE_ACCEPTED', 'Guard removed', '{name} accepted. The Guard between you two has been removed.', NULL, NULL, 'OTHER'),
 ('GUARD_DELETE_REFUSED', 'Guard stays', '{name} refused to remove the Guard between you two, so it stays as it is. You can ask again tomorrow, or remove it right away with coins.', NULL, NULL, 'OTHER'),
 ('GUARD_FORCE_DELETED', 'Guard removed', '{name} removed the Guard between you two.', NULL, NULL, 'OTHER')
ON CONFLICT (key) DO NOTHING;
