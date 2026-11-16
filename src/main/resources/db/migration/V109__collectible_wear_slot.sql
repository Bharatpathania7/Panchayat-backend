-- A picture / animation in the Parivar library can be something people WEAR: a frame, chat bubble, entry effect, badge or profile effect.
-- Won from a chest it then lands in the Bag with a Wear button (and runs for the days set on the reward).
ALTER TABLE parivar_collectibles ADD COLUMN wear_slot VARCHAR(10) CHECK (wear_slot IN ('FRAME', 'BUBBLE', 'ENTRY', 'BADGE', 'PROFILE'));

-- Items already in the library: the name tells what they are
UPDATE parivar_collectibles SET wear_slot = 'FRAME'  WHERE wear_slot IS NULL AND kind = 'ITEM' AND name ILIKE '%frame%';
UPDATE parivar_collectibles SET wear_slot = 'ENTRY'  WHERE wear_slot IS NULL AND kind = 'ITEM' AND name ILIKE '%entry%';
UPDATE parivar_collectibles SET wear_slot = 'BUBBLE' WHERE wear_slot IS NULL AND kind = 'ITEM' AND name ILIKE '%bubble%';
UPDATE parivar_collectibles SET wear_slot = 'BADGE'  WHERE wear_slot IS NULL AND kind = 'ITEM' AND name ILIKE '%badge%';

-- What people already won of those goes from the collection into the Bag, keeping its time left
INSERT INTO user_rewards (user_id, rule_id, rule_item_id, rule_kind, rule_name, item_name, item_type, gift_id, quantity, remaining, slot, media_url, media_format, granted_at, expires_at)
SELECT uc.user_id, NULL, NULL, 'GACHA', 'Parivar chest', uc.label, pc.wear_slot, NULL, 1, NULL, pc.wear_slot, pc.media_url, pc.media_format, uc.created_at, uc.expires_at
FROM user_collection uc
JOIN parivar_collectibles pc ON pc.name = uc.label AND pc.media_url = uc.image_url AND pc.wear_slot IS NOT NULL
WHERE uc.source = 'TREASURE' AND (uc.expires_at IS NULL OR uc.expires_at > now());

DELETE FROM user_collection uc USING parivar_collectibles pc
WHERE uc.source = 'TREASURE' AND pc.name = uc.label AND pc.media_url = uc.image_url AND pc.wear_slot IS NOT NULL;
