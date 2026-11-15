-- Badges won from chests / Gacha / spend rewards can be worn now: the ones already in Bags get their slot.
UPDATE user_rewards SET slot = 'BADGE' WHERE slot IS NULL AND item_type LIKE '%BADGE%';
