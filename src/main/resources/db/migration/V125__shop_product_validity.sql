-- Shop products: how many days a bought item lasts (0 = forever) and where it is worn (null = worked out from the category name).
ALTER TABLE shop_products ADD COLUMN IF NOT EXISTS valid_days INT NOT NULL DEFAULT 0 CHECK (valid_days >= 0);
ALTER TABLE shop_products ADD COLUMN IF NOT EXISTS wear_slot VARCHAR(12);

-- Things people already bought that can be worn (frames, chat bubbles, entry effects ...) move into the Bag as wearable items, one per piece owned.
INSERT INTO user_rewards (user_id, rule_id, rule_item_id, rule_kind, rule_name, item_name, item_type, gift_id, quantity, remaining, slot, media_url, media_format, granted_at, expires_at)
SELECT i.user_id, NULL, NULL, 'SHOP', 'Shop', left(p.name, 80),
       CASE WHEN c.name ILIKE '%bubble%' THEN 'BUBBLE' WHEN c.name ILIKE '%frame%' THEN 'FRAME' WHEN c.name ILIKE '%entry%' OR c.name ILIKE '%entrance%' THEN 'ENTRY' WHEN c.name ILIKE '%profile%' THEN 'PROFILE' ELSE 'BADGE' END,
       NULL, 1, NULL,
       CASE WHEN c.name ILIKE '%bubble%' THEN 'BUBBLE' WHEN c.name ILIKE '%frame%' THEN 'FRAME' WHEN c.name ILIKE '%entry%' OR c.name ILIKE '%entrance%' THEN 'ENTRY' WHEN c.name ILIKE '%profile%' THEN 'PROFILE' ELSE 'BADGE' END,
       p.asset_url, upper(substring(split_part(split_part(p.asset_url, '?', 1), '#', 1) from '\.([A-Za-z0-9]+)$')),
       i.first_bought_at, NULL
FROM user_shop_items i
JOIN shop_products p ON p.id = i.product_id
JOIN shop_categories c ON c.id = p.category_id
CROSS JOIN LATERAL generate_series(1, LEAST(i.quantity, 5)) g
WHERE i.quantity > 0 AND p.wear_slot IS NULL
  AND (c.name ILIKE '%bubble%' OR c.name ILIKE '%frame%' OR c.name ILIKE '%entry%' OR c.name ILIKE '%entrance%' OR c.name ILIKE '%profile%' OR c.name ILIKE '%badge%');

DELETE FROM user_shop_items i USING shop_products p, shop_categories c
WHERE p.id = i.product_id AND c.id = p.category_id AND p.wear_slot IS NULL
  AND (c.name ILIKE '%bubble%' OR c.name ILIKE '%frame%' OR c.name ILIKE '%entry%' OR c.name ILIKE '%entrance%' OR c.name ILIKE '%profile%' OR c.name ILIKE '%badge%');
