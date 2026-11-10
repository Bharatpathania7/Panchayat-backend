-- Newbie Welcome Rooms: welcome rooms hosted by staff, shown first in the app, with extra seats and a welcome gift.

-- Rooms flagged as newbie rooms are ordinary voice rooms, so seats, chat, gifts and LiveKit all keep working.
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS newbie BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS extra_seats BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_voice_rooms_newbie ON voice_rooms (newbie) WHERE newbie;

-- Room numbers come from the backend only: Newbie Welcome #10001, #10002, ...
CREATE SEQUENCE IF NOT EXISTS newbie_room_number_seq START 10001;

CREATE TABLE newbie_room_config (
    id                    INT PRIMARY KEY CHECK (id = 1),
    auto_create           BOOLEAN      NOT NULL DEFAULT FALSE,
    eligibility_days      INT          NOT NULL DEFAULT 7 CHECK (eligibility_days BETWEEN 1 AND 365),
    max_rooms             INT          NOT NULL DEFAULT 5 CHECK (max_rooms BETWEEN 1 AND 100),
    room_capacity         INT          NOT NULL DEFAULT 50 CHECK (room_capacity BETWEEN 16 AND 50),
    room_lifetime_hours   INT          NOT NULL DEFAULT 24 CHECK (room_lifetime_hours BETWEEN 1 AND 720),
    extra_seats_allowed   BOOLEAN      NOT NULL DEFAULT TRUE,
    host_grace_minutes    INT          NOT NULL DEFAULT 5 CHECK (host_grace_minutes BETWEEN 1 AND 120),
    welcome_gift_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    welcome_gift_id       VARCHAR(64)  NOT NULL DEFAULT 'welcome-newbie',
    welcome_title         VARCHAR(80)  NOT NULL DEFAULT 'Welcome to Panchayat!',
    welcome_message       VARCHAR(600) NOT NULL DEFAULT 'Your place to connect, talk, make new friends, join voice rooms, create families, send gifts and enjoy amazing conversations.',
    room_description      VARCHAR(300) NOT NULL DEFAULT 'A friendly room for new members. Say hi and meet the host!',
    priority              INT          NOT NULL DEFAULT 100
);
INSERT INTO newbie_room_config (id) VALUES (1) ON CONFLICT DO NOTHING;

-- People who may host a welcome room (chosen by the admin)
CREATE TABLE newbie_room_hosts (
    user_id    BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    added_by   VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE newbie_rooms (
    id                BIGSERIAL PRIMARY KEY,
    room_id           BIGINT       NOT NULL UNIQUE REFERENCES voice_rooms (id) ON DELETE CASCADE,
    room_number       INT          NOT NULL UNIQUE,
    name              VARCHAR(100) NOT NULL UNIQUE,
    host_id           BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    status            VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED', 'EXPIRED', 'CLOSED')),
    priority          INT          NOT NULL DEFAULT 100,
    expires_at        TIMESTAMPTZ,
    host_absent_since TIMESTAMPTZ,
    created_by        VARCHAR(60),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at         TIMESTAMPTZ
);
CREATE INDEX idx_newbie_rooms_status ON newbie_rooms (status, priority);
CREATE INDEX idx_newbie_rooms_host ON newbie_rooms (host_id);

-- One row per newbie: when they joined, which welcome room suits them, and whether the welcome and reward happened
CREATE TABLE newbie_user_status (
    user_id        BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    room_id        BIGINT REFERENCES voice_rooms (id) ON DELETE SET NULL,
    assigned_at    TIMESTAMPTZ,
    welcome_seen_at TIMESTAMPTZ,
    first_join_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE newbie_room_participants (
    room_id         BIGINT      NOT NULL REFERENCES voice_rooms (id) ON DELETE CASCADE,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    newbie          BOOLEAN     NOT NULL DEFAULT FALSE,
    joins           INT         NOT NULL DEFAULT 1,
    first_joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);
CREATE INDEX idx_newbie_participants_user ON newbie_room_participants (user_id);

-- A newbie gets the welcome gift once. The unique receiver stops duplicate rewards.
CREATE TABLE newbie_welcome_gifts (
    id          BIGSERIAL PRIMARY KEY,
    room_id     BIGINT      NOT NULL REFERENCES voice_rooms (id) ON DELETE CASCADE,
    host_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    receiver_id BIGINT      NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    gift_id     VARCHAR(64) NOT NULL,
    coins       BIGINT      NOT NULL,
    request_id  VARCHAR(80) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_newbie_welcome_gifts_host ON newbie_welcome_gifts (host_id);

CREATE TABLE newbie_room_events (
    id         BIGSERIAL PRIMARY KEY,
    room_id    BIGINT      REFERENCES voice_rooms (id) ON DELETE CASCADE,
    user_id    BIGINT,
    kind       VARCHAR(24) NOT NULL,
    detail     VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_newbie_events_room ON newbie_room_events (room_id, created_at DESC);

-- The Welcome Newbie gift: off sale until the admin adds its picture and animation (Admin > Gifts)
INSERT INTO gifts (id, name, price, category, sort_order, active)
VALUES ('welcome-newbie', 'Welcome Newbie 🎁', 500, 'Popular', 900, FALSE)
ON CONFLICT (id) DO NOTHING;
