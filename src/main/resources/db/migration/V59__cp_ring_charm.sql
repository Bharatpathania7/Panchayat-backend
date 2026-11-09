-- Charm per ring, set in the admin panel.
-- charm: what the person who receives the ring earns as profile Charm (NULL = the old rule, price / 5).
-- cp_charm: Charm the couple earns when the ring is added to their ring box (NULL or 0 = none).
ALTER TABLE cp_rings ADD COLUMN IF NOT EXISTS charm BIGINT CHECK (charm >= 0);
ALTER TABLE cp_rings ADD COLUMN IF NOT EXISTS cp_charm BIGINT CHECK (cp_charm >= 0);
