-- Lucky coins on gifts: every rule is set in the admin panel (nothing is fixed in the code any more).
ALTER TABLE gift_settings ADD COLUMN IF NOT EXISTS lucky_chance_percent   NUMERIC(5,2) NOT NULL DEFAULT 100 CHECK (lucky_chance_percent BETWEEN 0 AND 100);
ALTER TABLE gift_settings ADD COLUMN IF NOT EXISTS lucky_default_min      BIGINT NOT NULL DEFAULT 60000  CHECK (lucky_default_min >= 0);
ALTER TABLE gift_settings ADD COLUMN IF NOT EXISTS lucky_default_max      BIGINT NOT NULL DEFAULT 200000 CHECK (lucky_default_max >= 0);
ALTER TABLE gift_settings ADD COLUMN IF NOT EXISTS lucky_high_chance      NUMERIC(5,2) NOT NULL DEFAULT 1   CHECK (lucky_high_chance BETWEEN 0 AND 100);
ALTER TABLE gift_settings ADD COLUMN IF NOT EXISTS lucky_middle_chance    NUMERIC(5,2) NOT NULL DEFAULT 2.5 CHECK (lucky_middle_chance BETWEEN 0 AND 100);
ALTER TABLE gift_settings ADD COLUMN IF NOT EXISTS lucky_min_gift_coins   BIGINT NOT NULL DEFAULT 0 CHECK (lucky_min_gift_coins >= 0);
ALTER TABLE gift_settings ADD COLUMN IF NOT EXISTS lucky_daily_cap        BIGINT NOT NULL DEFAULT 1000000 CHECK (lucky_daily_cap >= 0);
ALTER TABLE gift_settings ADD COLUMN IF NOT EXISTS lucky_pair_daily_limit INT NOT NULL DEFAULT 3 CHECK (lucky_pair_daily_limit >= 0);

-- Every lucky payout: for the daily limits and for the admin's log
CREATE TABLE IF NOT EXISTS gift_lucky_log (
    id          BIGSERIAL PRIMARY KEY,
    receiver_id BIGINT NOT NULL,
    sender_id   BIGINT,
    gift_id     VARCHAR(80),
    gift_count  INT NOT NULL DEFAULT 1,
    gift_coins  BIGINT NOT NULL DEFAULT 0,
    amount      BIGINT NOT NULL CHECK (amount > 0),
    request_id  VARCHAR(80),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_gift_lucky_receiver_day ON gift_lucky_log (receiver_id, created_at);
CREATE INDEX IF NOT EXISTS ix_gift_lucky_pair_day ON gift_lucky_log (receiver_id, sender_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_gift_lucky_request ON gift_lucky_log (request_id) WHERE request_id IS NOT NULL;
