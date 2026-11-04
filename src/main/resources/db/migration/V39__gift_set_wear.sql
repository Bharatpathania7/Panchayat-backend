-- Gift-set pieces someone wears (e.g. the Storm Guardian frame and profile
-- page effect), so other people see them too: {"frame":"storm-guardian","page":"storm-guardian",...}
ALTER TABLE users ADD COLUMN IF NOT EXISTS gift_set_wear TEXT;
