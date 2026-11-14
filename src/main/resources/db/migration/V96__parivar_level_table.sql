-- Parivar level table: 11 levels. "Level N-1 -> N" asks for the activity points (exp_needed) and the gold fund (fund_needed) on row N.
-- Rows 2..7 keep their capacity / deputy / admin caps and frame; rows 8..11 are new.
INSERT INTO parivar_levels (level, exp_needed, fund_needed, capacity, deputy_cap, admin_cap, frame) VALUES
 (2,    100000,       2000000, 25, 1,  3, 'bronze'),
 (3,    150000,       6000000, 30, 2,  4, 'silver'),
 (4,    225000,      18000000, 35, 2,  5, 'gold'),
 (5,    340000,      54000000, 40, 3,  6, 'ruby'),
 (6,    510000,     162000000, 45, 3,  8, 'diamond'),
 (7,    760000,     486000000, 50, 4, 10, 'royal'),
 (8,   1140000,    1458000000, 55, 4, 12, 'royal'),
 (9,   1710000,    4374000000, 60, 5, 14, 'royal'),
 (10,  2560000,   13122000000, 65, 5, 16, 'royal'),
 (11,  3840000,   39366000000, 70, 6, 18, 'royal')
ON CONFLICT (level) DO UPDATE SET exp_needed = EXCLUDED.exp_needed, fund_needed = EXCLUDED.fund_needed;
