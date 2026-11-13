-- Official rooms of Panchayat: owned by the Sarpanch, who always sits on the owner seat. The admin makes them and picks their room admins.
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS official BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE voice_rooms SET official = TRUE WHERE owner_id IN (SELECT id FROM users WHERE official);
