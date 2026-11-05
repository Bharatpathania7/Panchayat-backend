-- Gift sets (Storm Guardian) no longer give cosmetics: clear what people were wearing.
UPDATE users SET gift_set_wear = NULL WHERE gift_set_wear IS NOT NULL;
