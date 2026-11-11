-- Weekly Parivar activity (from gifts) opens chests: 8k Normal, 24k Advanced, 60k Rare, 120k 2 x Rare.
INSERT INTO parivar_chest_types (name, price_funds, pieces, min_level, sort_order)
SELECT 'Advanced Chest', 5000, 10, 2, 15 WHERE NOT EXISTS (SELECT 1 FROM parivar_chest_types WHERE name = 'Advanced Chest');

INSERT INTO parivar_chest_rewards (chest_id, kind, amount, weight, label)
SELECT c.id, r.kind, r.amount, r.weight, r.label FROM parivar_chest_types c
JOIN (VALUES ('Advanced Chest', 'COINS', 300, 70, '300 coins'), ('Advanced Chest', 'CHARM', 150, 25, '150 Charm'), ('Advanced Chest', 'COINS', 3000, 5, '3,000 coins')) AS r (name, kind, amount, weight, label)
  ON r.name = c.name
WHERE NOT EXISTS (SELECT 1 FROM parivar_chest_rewards x WHERE x.chest_id = c.id);

CREATE TABLE parivar_activity_milestones (
    id            BIGSERIAL PRIMARY KEY,
    threshold     BIGINT  NOT NULL CHECK (threshold >= 1),
    chest_type_id BIGINT  NOT NULL REFERENCES parivar_chest_types (id) ON DELETE CASCADE,
    qty           INT     NOT NULL DEFAULT 1 CHECK (qty >= 1),
    active        BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE parivar_milestone_grants (
    key        VARCHAR(100) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO parivar_activity_milestones (threshold, chest_type_id, qty)
SELECT v.th, c.id, v.q FROM (VALUES (8000, 'Normal Chest', 1), (24000, 'Advanced Chest', 1), (60000, 'Rare Chest', 1), (120000, 'Rare Chest', 2)) AS v (th, name, q)
JOIN LATERAL (SELECT id FROM parivar_chest_types WHERE name = v.name ORDER BY id LIMIT 1) c ON TRUE;
