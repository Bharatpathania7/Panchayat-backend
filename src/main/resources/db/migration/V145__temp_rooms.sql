-- Temp rooms: anyone can make any number, deleted with all their data the moment the owner leaves.
-- A permanent room (temporary = false, the existing default) stays limited to one per owner (enforced in code).
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS temporary BOOLEAN NOT NULL DEFAULT FALSE;
