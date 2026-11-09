-- Longer signs (2 to 8) and a frame that grows with the Parivar's level (set by the admin per level).
ALTER TABLE parivars ALTER COLUMN sign TYPE VARCHAR(8);
ALTER TABLE parivar_levels ADD COLUMN IF NOT EXISTS frame VARCHAR(12) NOT NULL DEFAULT 'wood';
UPDATE parivar_levels SET frame = CASE level WHEN 1 THEN 'wood' WHEN 2 THEN 'bronze' WHEN 3 THEN 'silver' WHEN 4 THEN 'gold' WHEN 5 THEN 'ruby' WHEN 6 THEN 'diamond' ELSE 'royal' END;
