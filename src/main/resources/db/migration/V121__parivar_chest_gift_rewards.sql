-- Parivar chests can give gift cards (into the Bag) and Bag items, like Gacha does.
ALTER TABLE parivar_chest_rewards DROP CONSTRAINT IF EXISTS parivar_chest_rewards_kind_check;
ALTER TABLE parivar_chest_rewards ADD CONSTRAINT parivar_chest_rewards_kind_check
    CHECK (kind IN ('COINS', 'FUNDS', 'CHARM', 'CHEST', 'CARD', 'BARRIER', 'GIFT_CARD', 'ITEM'));
ALTER TABLE parivar_chest_rewards ADD COLUMN IF NOT EXISTS gift_id VARCHAR(64);
ALTER TABLE parivar_chest_rewards ADD COLUMN IF NOT EXISTS valid_days INTEGER NOT NULL DEFAULT 0;

-- A gift card in the pools of the three starting chests (the admin can change or remove these in the Parivar settings)
INSERT INTO parivar_chest_rewards (chest_id, kind, amount, weight, label, gift_id)
SELECT c.id, 'GIFT_CARD', 1, 20, r.label, r.gift
FROM parivar_chest_types c
JOIN (VALUES ('Normal Chest', 'cupid-heart', 'Cupid Heart Gift Card'), ('Rare Chest', 'love-fireworks', 'Love Fireworks Gift Card'), ('Advanced Chest', 'city-fireworks', 'City Fireworks Gift Card')) AS r (name, gift, label)
  ON r.name = c.name
WHERE NOT EXISTS (SELECT 1 FROM parivar_chest_rewards x WHERE x.chest_id = c.id AND x.kind = 'GIFT_CARD');
