-- CP level now comes only from Charm earned by gifts between the two partners.
-- cp_couples.love is the couple's shared Charm balance (the column keeps its old name).

-- Charm a gift earns a CP when sent between partners (per gift). NULL = 1 Charm per 10 coins.
ALTER TABLE gifts ADD COLUMN IF NOT EXISTS cp_charm BIGINT CHECK (cp_charm >= 0);

-- Every gift that earned the couple Charm. request_id is unique, so a retried gift never counts twice.
CREATE TABLE IF NOT EXISTS cp_charm_log (
    id          BIGSERIAL PRIMARY KEY,
    couple_id   BIGINT       NOT NULL REFERENCES cp_couples (id) ON DELETE CASCADE,
    sender_id   BIGINT       NOT NULL,
    receiver_id BIGINT       NOT NULL,
    gift_id     VARCHAR(64)  NOT NULL,
    gift_count  INT          NOT NULL,
    coins       BIGINT       NOT NULL,
    charm       BIGINT       NOT NULL,
    request_id  VARCHAR(80),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_cp_charm_log_request ON cp_charm_log (request_id) WHERE request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_cp_charm_log_couple ON cp_charm_log (couple_id, id DESC);

-- When each badge level was reached (level 1 is the day the couple began).
CREATE TABLE IF NOT EXISTS cp_badge_unlocks (
    couple_id   BIGINT      NOT NULL REFERENCES cp_couples (id) ON DELETE CASCADE,
    level       INT         NOT NULL,
    unlocked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (couple_id, level)
);

-- Couples that already have Charm: the levels they have reached under the new table
INSERT INTO cp_badge_unlocks (couple_id, level, unlocked_at)
SELECT c.id, t.lvl, c.since
FROM cp_couples c
JOIN (VALUES (2, 5000), (3, 15000), (4, 35000), (5, 75000), (6, 150000), (7, 250000), (8, 400000), (9, 600000), (10, 1000000)) AS t (lvl, need)
  ON c.love >= t.need
ON CONFLICT DO NOTHING;
