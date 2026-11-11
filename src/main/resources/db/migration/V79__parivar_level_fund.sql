-- A Parivar no longer levels up by itself. To go up a level it needs (1) the activity (EXP) and (2) the family fund the level asks for;
-- the Mukhiya then presses Level up and that fund is spent. fund_needed is set per level in the admin panel.
ALTER TABLE parivar_levels ADD COLUMN IF NOT EXISTS fund_needed BIGINT NOT NULL DEFAULT 0 CHECK (fund_needed >= 0);
UPDATE parivar_levels SET fund_needed = exp_needed / 5 WHERE level > 1 AND fund_needed = 0;
