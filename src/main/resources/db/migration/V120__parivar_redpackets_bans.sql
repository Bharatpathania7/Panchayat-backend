-- Parivar: red packets the Mukhiya sends to the family, and the people the Mukhiya has banned.
CREATE TABLE parivar_redpackets (
    id          BIGSERIAL PRIMARY KEY,
    parivar_id  BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    sender_id   BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source      VARCHAR(8)  NOT NULL,                    -- COINS (the sender's wallet) or FUNDS (the Parivar treasury)
    total       BIGINT      NOT NULL,
    pieces      INTEGER     NOT NULL,
    claimed     INTEGER     NOT NULL DEFAULT 0,
    paid        BIGINT      NOT NULL DEFAULT 0,
    equal       BOOLEAN     NOT NULL DEFAULT FALSE,
    message     VARCHAR(80),
    status      VARCHAR(8)  NOT NULL DEFAULT 'OPEN',     -- OPEN, DONE, EXPIRED
    send_key    VARCHAR(100) UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_parivar_redpackets_p ON parivar_redpackets (parivar_id, id DESC);
CREATE INDEX idx_parivar_redpackets_open ON parivar_redpackets (status, expires_at);

CREATE TABLE parivar_redpacket_claims (
    packet_id  BIGINT      NOT NULL REFERENCES parivar_redpackets (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    coins      BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (packet_id, user_id)
);

CREATE TABLE parivar_bans (
    parivar_id BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    banned_by  BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    reason     VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (parivar_id, user_id)
);
