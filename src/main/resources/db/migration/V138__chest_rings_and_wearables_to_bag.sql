-- Chest rewards that were only kept in the profile collection (no button in the Bag) move to where they can be used:
--  * rings   -> the CP ring bag (Bag shows "Add to CP")
--  * frames / bubbles / entries / badges whose slot came from the library category, not the name -> the Bag with a Wear button

-- 1) Rings
INSERT INTO cp_rings (id, name, price, rarity, image_url, sort_order, active, charm, cp_charm)
SELECT DISTINCT 'lib-' || pc.id, left(pc.name, 60), 1, 'Chest', CASE WHEN length(pc.media_url) <= 300 THEN pc.media_url END, 300, FALSE, 0, 0
FROM user_collection uc
JOIN parivar_collectibles pc ON pc.name = uc.label AND pc.media_url = uc.image_url
LEFT JOIN parivar_library_categories k ON k.id = pc.category_id
WHERE uc.source = 'TREASURE' AND pc.wear_slot IS NULL
  AND (uc.expires_at IS NULL OR uc.expires_at > now())
  AND (pc.name ~* '\mrings?\M' OR k.name ~* '\mrings?\M')
ON CONFLICT (id) DO NOTHING;

INSERT INTO cp_ring_bag (user_id, ring_id, quantity)
SELECT uc.user_id, 'lib-' || pc.id, count(*)
FROM user_collection uc
JOIN parivar_collectibles pc ON pc.name = uc.label AND pc.media_url = uc.image_url
LEFT JOIN parivar_library_categories k ON k.id = pc.category_id
WHERE uc.source = 'TREASURE' AND pc.wear_slot IS NULL
  AND (uc.expires_at IS NULL OR uc.expires_at > now())
  AND (pc.name ~* '\mrings?\M' OR k.name ~* '\mrings?\M')
GROUP BY uc.user_id, pc.id
ON CONFLICT (user_id, ring_id) DO UPDATE SET quantity = cp_ring_bag.quantity + excluded.quantity;

DELETE FROM user_collection uc USING parivar_collectibles pc
LEFT JOIN parivar_library_categories k ON k.id = pc.category_id
WHERE uc.source = 'TREASURE' AND pc.name = uc.label AND pc.media_url = uc.image_url AND pc.wear_slot IS NULL
  AND (pc.name ~* '\mrings?\M' OR k.name ~* '\mrings?\M');

-- 2) Wearables told by the library category ("Chat Frames", "Bubbles", ...)
INSERT INTO user_rewards (user_id, rule_id, rule_item_id, rule_kind, rule_name, item_name, item_type, gift_id, quantity, remaining, slot, media_url, media_format, granted_at, expires_at)
SELECT uc.user_id, NULL, NULL, 'GACHA', 'Parivar chest', uc.label, s.slot, NULL, 1, NULL, s.slot, pc.media_url, pc.media_format, uc.created_at, uc.expires_at
FROM user_collection uc
JOIN parivar_collectibles pc ON pc.name = uc.label AND pc.media_url = uc.image_url
LEFT JOIN parivar_library_categories k ON k.id = pc.category_id
CROSS JOIN LATERAL (SELECT CASE
        WHEN k.name ILIKE '%bubble%' THEN 'BUBBLE'
        WHEN k.name ILIKE '%frame%'  THEN 'FRAME'
        WHEN k.name ILIKE '%entry%'  THEN 'ENTRY'
        WHEN k.name ILIKE '%badge%'  THEN 'BADGE' END AS slot) s
WHERE uc.source = 'TREASURE' AND pc.wear_slot IS NULL AND s.slot IS NOT NULL
  AND (uc.expires_at IS NULL OR uc.expires_at > now());

DELETE FROM user_collection uc USING parivar_collectibles pc
LEFT JOIN parivar_library_categories k ON k.id = pc.category_id
WHERE uc.source = 'TREASURE' AND pc.name = uc.label AND pc.media_url = uc.image_url AND pc.wear_slot IS NULL
  AND (k.name ILIKE '%bubble%' OR k.name ILIKE '%frame%' OR k.name ILIKE '%entry%' OR k.name ILIKE '%badge%');
