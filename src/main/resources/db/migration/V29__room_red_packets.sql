-- Red packets dropped in rooms, run by the server: the pot, its fair split and who grabbed what.
CREATE TABLE room_red_packets (
    id            BIGSERIAL PRIMARY KEY,
    room_id       BIGINT      NOT NULL REFERENCES voice_rooms (id) ON DELETE CASCADE,
    sender_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    amount        BIGINT      NOT NULL CHECK (amount > 0),
    cover         VARCHAR(20) NOT NULL DEFAULT 'red',
    paid_with     VARCHAR(10) NOT NULL CHECK (paid_with IN ('BAG', 'COINS')),
    coins_left    BIGINT      NOT NULL CHECK (coins_left >= 0),
    shares_left   INT         NOT NULL CHECK (shares_left >= 0),
    rain_at       TIMESTAMPTZ NOT NULL,
    end_at        TIMESTAMPTZ NOT NULL,
    settled       BOOLEAN     NOT NULL DEFAULT FALSE,
    refunded      BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_room_red_packets_room ON room_red_packets (room_id, created_at DESC);
CREATE INDEX idx_room_red_packets_open ON room_red_packets (end_at) WHERE NOT settled;

CREATE TABLE room_red_packet_grabs (
    id         BIGSERIAL PRIMARY KEY,
    packet_id  BIGINT      NOT NULL REFERENCES room_red_packets (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    coins      BIGINT      NOT NULL CHECK (coins > 0),
    grabbed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_room_red_packet_grabs ON room_red_packet_grabs (packet_id, user_id);
