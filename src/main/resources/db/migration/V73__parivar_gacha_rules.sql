-- Parivar Gacha rules: one free roll a day paid from the Parivar fund, then a personal Gacha ticket or the player's own gold.
-- Two machines: Normal (no chests) and Advanced (opens at Parivar level 4).

ALTER TABLE parivar_gacha_machines ADD COLUMN IF NOT EXISTS free_fund_cost BIGINT NOT NULL DEFAULT 2000 CHECK (free_fund_cost >= 0);
ALTER TABLE parivar_gacha_machines ADD COLUMN IF NOT EXISTS roll_price_coins BIGINT NOT NULL DEFAULT 1000 CHECK (roll_price_coins >= 0);
ALTER TABLE parivar_settings ADD COLUMN IF NOT EXISTS gacha_ticket_price BIGINT NOT NULL DEFAULT 1000 CHECK (gacha_ticket_price >= 0);

-- Gacha tickets belong to a person, not to the Parivar
CREATE TABLE IF NOT EXISTS parivar_user_tickets (
    user_id    BIGINT PRIMARY KEY,
    qty        BIGINT      NOT NULL DEFAULT 0 CHECK (qty >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- New reward kinds: GIFT_CARD (a gift into the Bag), WEAPON (extra Chori attempts for the Parivar),
-- ITEM (a frame, chat bubble or entry animation from Spend rewards, for a number of days)
ALTER TABLE parivar_gacha_rewards ADD COLUMN IF NOT EXISTS gift_id VARCHAR(64);
ALTER TABLE parivar_gacha_rewards DROP CONSTRAINT IF EXISTS parivar_gacha_rewards_kind_check;
ALTER TABLE parivar_gacha_rewards ADD CONSTRAINT parivar_gacha_rewards_kind_check
    CHECK (kind IN ('COINS', 'FUNDS', 'CHARM', 'CHEST', 'CARD', 'BARRIER', 'GIFT_CARD', 'WEAPON', 'ITEM'));
ALTER TABLE parivar_inventory DROP CONSTRAINT IF EXISTS parivar_inventory_kind_check;
ALTER TABLE parivar_inventory ADD CONSTRAINT parivar_inventory_kind_check CHECK (kind IN ('GACHA_CARD', 'CHEST', 'BARRIER', 'ATTEMPT'));

-- The two machines
UPDATE parivar_gacha_machines SET name = 'Normal Gacha', tier = 'NORMAL', price_funds = 0, service_fee_coins = 0, free_per_day = 1,
       free_fund_cost = 2000, roll_price_coins = 1000, min_level = 1, sort_order = 1, active = TRUE
 WHERE tier = 'MEDIUM' OR name = 'Medium Gacha';
UPDATE parivar_gacha_machines SET name = 'Advanced Gacha', tier = 'ADVANCED', price_funds = 0, service_fee_coins = 0, free_per_day = 1,
       free_fund_cost = 2000, roll_price_coins = 1000, min_level = 4, sort_order = 2, active = TRUE
 WHERE (tier = 'ADVANCED' AND name <> 'Normal Gacha') OR name = 'Advanced Gacha';
UPDATE parivar_gacha_machines SET active = FALSE WHERE tier = 'SUPER' OR name = 'Super Gacha';

DELETE FROM parivar_gacha_rewards WHERE machine_id IN (SELECT id FROM parivar_gacha_machines WHERE tier IN ('NORMAL', 'ADVANCED'));

-- Gold and Parivar fund: 200 / 400 / 800 in both machines
INSERT INTO parivar_gacha_rewards (machine_id, kind, amount, weight, rarity, label)
SELECT m.id, r.kind, r.amount, r.weight, r.rarity, r.label
FROM parivar_gacha_machines m
JOIN (VALUES ('COINS', 200, 2200, 'COMMON', '200 Gold'), ('COINS', 400, 1300, 'RARE', '400 Gold'), ('COINS', 800, 700, 'EPIC', '800 Gold'),
             ('FUNDS', 200, 2200, 'COMMON', '200 Family Fund'), ('FUNDS', 400, 1300, 'RARE', '400 Family Fund'), ('FUNDS', 800, 700, 'EPIC', '800 Family Fund')) AS r (kind, amount, weight, rarity, label) ON TRUE
WHERE m.tier = 'NORMAL';
INSERT INTO parivar_gacha_rewards (machine_id, kind, amount, weight, rarity, label)
SELECT m.id, r.kind, r.amount, r.weight, r.rarity, r.label
FROM parivar_gacha_machines m
JOIN (VALUES ('COINS', 200, 2000, 'COMMON', '200 Gold'), ('COINS', 400, 1200, 'RARE', '400 Gold'), ('COINS', 800, 700, 'EPIC', '800 Gold'),
             ('FUNDS', 200, 2000, 'COMMON', '200 Family Fund'), ('FUNDS', 400, 1200, 'RARE', '400 Family Fund'), ('FUNDS', 800, 700, 'EPIC', '800 Family Fund')) AS r (kind, amount, weight, rarity, label) ON TRUE
WHERE m.tier = 'ADVANCED';

-- Two kinds of small gift cards in each machine (the cheapest gifts on sale; change them in Admin > Parivar > Gacha)
INSERT INTO parivar_gacha_rewards (machine_id, kind, gift_id, amount, weight, rarity, label)
SELECT m.id, 'GIFT_CARD', g.id, 1, CASE WHEN g.rn = 1 THEN 600 ELSE 400 END, 'COMMON', g.name || ' Gift Card'
FROM parivar_gacha_machines m
JOIN (SELECT id, name, row_number() OVER (ORDER BY price, id) AS rn FROM gifts WHERE active AND id <> 'welcome-newbie') g ON g.rn <= 2
WHERE m.tier = 'NORMAL';
INSERT INTO parivar_gacha_rewards (machine_id, kind, gift_id, amount, weight, rarity, label)
SELECT m.id, 'GIFT_CARD', g.id, 1, CASE WHEN g.rn = 3 THEN 600 ELSE 400 END, 'COMMON', g.name || ' Gift Card'
FROM parivar_gacha_machines m
JOIN (SELECT id, name, row_number() OVER (ORDER BY price, id) AS rn FROM gifts WHERE active AND id <> 'welcome-newbie') g ON g.rn IN (3, 4)
WHERE m.tier = 'ADVANCED';

-- Shastra (Stealing Attempts) and Kavach in both machines
INSERT INTO parivar_gacha_rewards (machine_id, kind, amount, weight, rarity, label)
SELECT m.id, 'WEAPON', 1, 300, 'RARE', 'Stealing Attempt' FROM parivar_gacha_machines m WHERE m.tier IN ('NORMAL', 'ADVANCED');
INSERT INTO parivar_gacha_rewards (machine_id, kind, ref, amount, weight, rarity, label)
SELECT m.id, 'BARRIER', b.id, 1, 300, 'RARE', 'Kavach'
FROM parivar_gacha_machines m JOIN (SELECT id FROM parivar_barrier_types WHERE active ORDER BY id LIMIT 1) b ON TRUE
WHERE m.tier IN ('NORMAL', 'ADVANCED');

-- Chests only in the Advanced machine: Normal Chest 5%, Advanced Chest 1% (weights out of 10,000)
INSERT INTO parivar_gacha_rewards (machine_id, kind, ref, amount, weight, rarity, label)
SELECT m.id, 'CHEST', c.id, 1, 500, 'RARE', 'Normal Chest'
FROM parivar_gacha_machines m JOIN (SELECT id FROM parivar_chest_types WHERE name = 'Normal Chest' ORDER BY id LIMIT 1) c ON TRUE WHERE m.tier = 'ADVANCED';
INSERT INTO parivar_gacha_rewards (machine_id, kind, ref, amount, weight, rarity, label)
SELECT m.id, 'CHEST', c.id, 1, 100, 'LEGENDARY', 'Advanced Chest'
FROM parivar_gacha_machines m JOIN (SELECT id FROM parivar_chest_types WHERE name <> 'Normal Chest' ORDER BY price_funds DESC, id LIMIT 1) c ON TRUE WHERE m.tier = 'ADVANCED';
