-- Voice room levels from room EXP (10 levels). A room earns EXP from people spending time in it
-- and from gifts / red packets sent in it, up to a daily cap. Locked (private or password) rooms earn none.
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS exp BIGINT NOT NULL DEFAULT 0;

-- What each room earned per day (India time), so the daily caps can be kept
CREATE TABLE IF NOT EXISTS room_exp_daily (
    room_id   BIGINT NOT NULL REFERENCES voice_rooms (id) ON DELETE CASCADE,
    day       DATE   NOT NULL,
    voice_exp BIGINT NOT NULL DEFAULT 0,
    gift_exp  BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (room_id, day)
);
