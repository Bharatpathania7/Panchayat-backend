-- CP (couples): proposals with a ring, the couple, its ring box and blessings.

CREATE TABLE couples (
    id                 BIGSERIAL PRIMARY KEY,
    user_a             BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE, -- proposed
    user_b             BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE, -- said yes
    love               BIGINT      NOT NULL DEFAULT 0 CHECK (love >= 0),
    message            VARCHAR(120),
    worn_ring_id       BIGINT REFERENCES shop_products (id) ON DELETE SET NULL,
    end_requested_by   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    end_requested_at   TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at           TIMESTAMPTZ,
    CHECK (user_a <> user_b)
);
-- One live CP per person (as either side)
CREATE UNIQUE INDEX uq_couples_a_live ON couples (user_a) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX uq_couples_b_live ON couples (user_b) WHERE ended_at IS NULL;

CREATE TABLE couple_proposals (
    id          BIGSERIAL PRIMARY KEY,
    from_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    to_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    ring_id     BIGINT      NOT NULL REFERENCES shop_products (id) ON DELETE CASCADE,
    message     VARCHAR(120),
    status      VARCHAR(12) NOT NULL DEFAULT 'PENDING', -- PENDING ACCEPTED DECLINED CANCELLED EXPIRED
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at TIMESTAMPTZ,
    CHECK (from_id <> to_id)
);
-- One open proposal from each person at a time
CREATE UNIQUE INDEX uq_couple_proposals_open ON couple_proposals (from_id) WHERE status = 'PENDING';
CREATE INDEX idx_couple_proposals_to ON couple_proposals (to_id, status);

-- Rings the couple has: the proposal ring and every ring given after
CREATE TABLE couple_rings (
    id         BIGSERIAL PRIMARY KEY,
    couple_id  BIGINT      NOT NULL REFERENCES couples (id) ON DELETE CASCADE,
    ring_id    BIGINT      NOT NULL REFERENCES shop_products (id) ON DELETE CASCADE,
    given_by   BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_couple_rings_couple ON couple_rings (couple_id, created_at DESC);

CREATE TABLE couple_blessings (
    id         BIGSERIAL PRIMARY KEY,
    couple_id  BIGINT      NOT NULL REFERENCES couples (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    coins      BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_couple_blessings_couple ON couple_blessings (couple_id, created_at DESC);
