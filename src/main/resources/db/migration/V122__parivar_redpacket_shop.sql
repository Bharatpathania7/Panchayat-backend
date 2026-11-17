-- Parivar red packets are bought in the Parivar shop (types the admin sets), kept in the inventory, and sent by the Mukhiya
-- (or whoever the admin allows: the SEND_REDPACKET permission). The admin can also send a red packet to a Parivar.
CREATE TABLE parivar_redpacket_types (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(40) NOT NULL,
    coins      BIGINT      NOT NULL CHECK (coins >= 1),     -- what the red packet holds
    pieces     INTEGER     NOT NULL DEFAULT 10 CHECK (pieces >= 1),
    sort_order INTEGER     NOT NULL DEFAULT 0,
    active     BOOLEAN     NOT NULL DEFAULT TRUE
);
INSERT INTO parivar_redpacket_types (name, coins, pieces, sort_order) VALUES
    ('Chhota Red Packet', 1000, 5, 1), ('Bada Red Packet', 10000, 10, 2), ('Shahi Red Packet', 100000, 20, 3);

DO $$
DECLARE c text;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE conrelid = 'parivar_inventory'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%GACHA_CARD%' LOOP
        EXECUTE format('ALTER TABLE parivar_inventory DROP CONSTRAINT %I', c);
    END LOOP;
    FOR c IN SELECT conname FROM pg_constraint WHERE conrelid = 'parivar_shop_items'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%GACHA_CARD%' LOOP
        EXECUTE format('ALTER TABLE parivar_shop_items DROP CONSTRAINT %I', c);
    END LOOP;
END $$;
ALTER TABLE parivar_inventory ADD CONSTRAINT parivar_inventory_kind_check CHECK (kind IN ('GACHA_CARD', 'CHEST', 'BARRIER', 'ATTEMPT', 'REDPACKET'));
ALTER TABLE parivar_shop_items ADD CONSTRAINT parivar_shop_items_kind_check CHECK (kind IN ('GACHA_CARD', 'CHEST', 'BARRIER', 'REDPACKET'));

-- In the shop: priced like the coins they hold (1 for 1), in the treasury's funds or the buyer's coins
INSERT INTO parivar_shop_items (name, kind, ref, qty, price_funds, price_coins, sort_order)
SELECT t.name, 'REDPACKET', t.id, 1, t.coins, t.coins, 30 + t.id FROM parivar_redpacket_types t
WHERE NOT EXISTS (SELECT 1 FROM parivar_shop_items s WHERE s.kind = 'REDPACKET' AND s.ref = t.id);

ALTER TABLE parivar_redpackets ADD COLUMN IF NOT EXISTS type_id BIGINT;
ALTER TABLE parivar_redpackets ALTER COLUMN source TYPE VARCHAR(10);

-- Who may send a red packet besides the Mukhiya: nobody until the admin ticks it
INSERT INTO parivar_permissions (role, perm, allowed) VALUES ('DEPUTY', 'SEND_REDPACKET', FALSE), ('ADMIN', 'SEND_REDPACKET', FALSE), ('MEMBER', 'SEND_REDPACKET', FALSE)
ON CONFLICT (role, perm) DO NOTHING;
