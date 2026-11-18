-- Chest rewards that are worn or kept (frames, bubbles, entries, badges, titles, collectibles) last 3 days, not for ever.

-- 1. Library items whose name says what they are become wearable (Wear button in the Bag)
UPDATE parivar_collectibles SET wear_slot = 'BUBBLE' WHERE wear_slot IS NULL AND name ILIKE '%bubble%';
UPDATE parivar_collectibles SET wear_slot = 'FRAME'  WHERE wear_slot IS NULL AND name ILIKE '%frame%';
UPDATE parivar_collectibles SET wear_slot = 'ENTRY'  WHERE wear_slot IS NULL AND name ILIKE '%entry%';
UPDATE parivar_collectibles SET wear_slot = 'BADGE'  WHERE wear_slot IS NULL AND name ILIKE '%badge%';

-- 2. Chest rewards set to "never runs out" now last 3 days (the admin can still set another number)
UPDATE treasure_rewards SET valid_days = 3 WHERE valid_days = 0 AND kind IN ('COLLECTIBLE', 'TITLE', 'BADGE');

-- 3. What people already won of the wearable ones moves from the collection into the Bag (Wear button), 3 days from now
INSERT INTO user_rewards (user_id, rule_id, rule_item_id, rule_kind, rule_name, item_name, item_type, gift_id, quantity, remaining, slot, media_url, media_format, granted_at, expires_at)
SELECT uc.user_id, NULL, NULL, 'GACHA', 'Parivar chest', uc.label, pc.wear_slot, NULL, 1, NULL, pc.wear_slot, pc.media_url, pc.media_format, uc.created_at,
       COALESCE(uc.expires_at, now() + interval '3 days')
FROM user_collection uc
JOIN parivar_collectibles pc ON pc.name = uc.label AND pc.media_url = uc.image_url AND pc.wear_slot IS NOT NULL
WHERE uc.source = 'TREASURE' AND (uc.expires_at IS NULL OR uc.expires_at > now());

DELETE FROM user_collection uc USING parivar_collectibles pc
WHERE uc.source = 'TREASURE' AND pc.name = uc.label AND pc.media_url = uc.image_url AND pc.wear_slot IS NOT NULL;

-- 4. Chest rewards already won that never ran out: 3 days from now
UPDATE user_rewards SET expires_at = now() + interval '3 days' WHERE rule_name = 'Parivar chest' AND expires_at IS NULL;
UPDATE user_collection SET expires_at = now() + interval '3 days' WHERE source = 'TREASURE' AND expires_at IS NULL;
