-- VIP now goes up to 11 (was capped at 7): widen the vip_cosmetics check constraint.
ALTER TABLE vip_cosmetics DROP CONSTRAINT vip_cosmetics_vip_level_check;
ALTER TABLE vip_cosmetics ADD CONSTRAINT vip_cosmetics_vip_level_check CHECK (vip_level BETWEEN 1 AND 11);
