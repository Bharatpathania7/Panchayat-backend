-- Per gift, set in the admin panel: how much Charm and how many lucky coins it triggers,
-- each as a minimum and maximum (a random number in between). NULL = the default.
ALTER TABLE gifts ADD COLUMN IF NOT EXISTS charm_min BIGINT CHECK (charm_min >= 0);
ALTER TABLE gifts ADD COLUMN IF NOT EXISTS charm_max BIGINT CHECK (charm_max >= 0);
ALTER TABLE gifts ADD COLUMN IF NOT EXISTS coin_min BIGINT CHECK (coin_min >= 0);
ALTER TABLE gifts ADD COLUMN IF NOT EXISTS coin_max BIGINT CHECK (coin_max >= 0);

-- A fixed Charm set earlier becomes a range with the same minimum and maximum
UPDATE gifts SET charm_min = charm, charm_max = charm WHERE charm IS NOT NULL AND charm_min IS NULL;
