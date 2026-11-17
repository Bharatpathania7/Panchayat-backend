-- Name frames: an avatar frame with a name plate. The plate shows the wearer's account name. The admin makes them, gives them out by top-up amount or to a person.
CREATE TABLE name_frames (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(40)   NOT NULL,
    image_url    VARCHAR(500)  NOT NULL,
    -- how the frame fits the avatar: frame width / avatar width, and where the avatar's centre is down the frame (0..1)
    frame_scale  NUMERIC(6,3)  NOT NULL DEFAULT 1.406 CHECK (frame_scale BETWEEN 1 AND 3),
    frame_y      NUMERIC(6,3)  NOT NULL DEFAULT 0.563 CHECK (frame_y BETWEEN 0.2 AND 0.9),
    -- the name plate, as a share (percent) of the frame image
    plate_x      NUMERIC(5,1)  NOT NULL DEFAULT 21.1,
    plate_y      NUMERIC(5,1)  NOT NULL DEFAULT 73.8,
    plate_w      NUMERIC(5,1)  NOT NULL DEFAULT 58.2,
    plate_h      NUMERIC(5,1)  NOT NULL DEFAULT 16.7,
    text_color   VARCHAR(9)    NOT NULL DEFAULT '#ffe9a8',
    stroke_color VARCHAR(9)    NOT NULL DEFAULT '#0a2a80',
    active       BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order   INT           NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- Automatic rules: top-up rupees. days = 0 -> it never ends.
CREATE TABLE name_frame_rules (
    id       BIGSERIAL PRIMARY KEY,
    frame_id BIGINT      NOT NULL REFERENCES name_frames (id) ON DELETE CASCADE,
    kind     VARCHAR(12) NOT NULL CHECK (kind IN ('TOPUP_TOTAL', 'TOPUP_ONE')),
    value    BIGINT      NOT NULL CHECK (value >= 1),
    days     INT         NOT NULL DEFAULT 0 CHECK (days >= 0),
    active   BOOLEAN     NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_name_frame_rules_frame ON name_frame_rules (frame_id);
ALTER TABLE user_rewards ADD COLUMN IF NOT EXISTS name_frame_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_user_rewards_name_frame ON user_rewards (name_frame_id) WHERE name_frame_id IS NOT NULL;

-- The first one (the crown frame): given to everyone whose top-ups reach Rs 10,000
INSERT INTO name_frames (name, image_url, sort_order) VALUES ('Crown frame', '/frames/crown-name.png', 1);
INSERT INTO name_frame_rules (frame_id, kind, value, days) SELECT id, 'TOPUP_TOTAL', 10000, 0 FROM name_frames WHERE name = 'Crown frame';
