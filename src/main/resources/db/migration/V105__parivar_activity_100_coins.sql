-- Family activity: 100 coins = 1 activity (EXP) for gifts and donations.
-- Only where the value is still the old default (10), so nothing the admin already set is overwritten.
-- The admin changes both numbers in Parivar > Settings > Activity and treasury.
UPDATE parivar_settings SET gift_coins_per_exp = 100 WHERE gift_coins_per_exp = 10;
UPDATE parivar_settings SET donation_coins_per_exp = 100 WHERE donation_coins_per_exp = 10;
