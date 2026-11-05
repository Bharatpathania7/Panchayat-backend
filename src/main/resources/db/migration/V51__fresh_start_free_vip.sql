-- Fresh start (one time): every user's charm, level (EXP) and VIP go back to zero,
-- then everyone gets 3 days of free VIP 1. After that VIP has to be bought.
-- The official Sarpanch account is left alone.

UPDATE users
SET charm_points    = 0,
    exp             = 0,
    exp_points      = 0,
    level           = 1,
    vip_points      = 0,
    admin_vip_level = NULL,
    vip_level       = 1,
    vip_active      = TRUE,
    vip_expires_at  = now() + INTERVAL '3 days'
WHERE NOT coalesce(official, FALSE);

-- Today's EXP caps start over too
DELETE FROM user_exp_daily;

-- VIP rewards above VIP 1 are taken back (they come back as people level up again)
DELETE FROM user_equipped_cosmetics e USING vip_cosmetics c WHERE c.id = e.cosmetic_id AND c.vip_level > 1;
DELETE FROM user_unlocked_cosmetics u USING vip_cosmetics c WHERE c.id = u.cosmetic_id AND c.vip_level > 1;
