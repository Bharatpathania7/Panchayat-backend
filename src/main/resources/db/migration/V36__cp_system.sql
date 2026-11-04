-- CP (Couple Partner) system, replacing the first version from V32 (never released).
DROP TABLE IF EXISTS couple_blessings;
DROP TABLE IF EXISTS couple_rings;
DROP TABLE IF EXISTS couple_proposals;
DROP TABLE IF EXISTS couples;

-- Ring catalogue. Ids are stable: the app and people's bags use them.
CREATE TABLE cp_rings (
    id         VARCHAR(40)  PRIMARY KEY,
    name       VARCHAR(60)  NOT NULL,
    price      BIGINT       NOT NULL CHECK (price > 0),
    rarity     VARCHAR(20)  NOT NULL,
    image_url  VARCHAR(300),
    sort_order INTEGER      NOT NULL DEFAULT 0,
    active     BOOLEAN      NOT NULL DEFAULT TRUE
);
INSERT INTO cp_rings (id, name, price, rarity, image_url, sort_order) VALUES
    ('phool-kali',     'Phool Kali',      999,   'Common',    '/rings/phool-kali.png',     1),
    ('moti-seep',      'Moti Seep',       1999,  'Common',    '/rings/moti-seep.png',      2),
    ('laal-bel',       'Laal Bel',        2999,  'Rare',      '/rings/laal-bel.png',       3),
    ('titli-rani',     'Titli Rani',      3999,  'Rare',      '/rings/titli-rani.png',     4),
    ('gulabi-dil',     'Gulabi Dil',      5200,  'Epic',      '/rings/gulabi-dil.png',     5),
    ('taara-boond',    'Taara Boond',     7777,  'Epic',      '/rings/taara-boond.png',    6),
    ('samundar-lehar', 'Samundar Lehar',  9999,  'Epic',      '/rings/samundar-lehar.png', 7),
    ('chand-titli',    'Chand Titli',     13140, 'Legendary', '/rings/chand-titli.png',    8);

-- Unused rings people own
CREATE TABLE cp_ring_bag (
    user_id  BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    ring_id  VARCHAR(40) NOT NULL REFERENCES cp_rings (id),
    quantity INTEGER     NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    PRIMARY KEY (user_id, ring_id)
);

CREATE TABLE cp_proposals (
    id          BIGSERIAL PRIMARY KEY,
    from_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    to_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    ring_id     VARCHAR(40)  NOT NULL REFERENCES cp_rings (id),
    message     VARCHAR(120),
    status      VARCHAR(12)  NOT NULL DEFAULT 'PENDING', -- PENDING ACCEPTED DECLINED CANCELLED EXPIRED
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    answered_at TIMESTAMPTZ,
    CHECK (from_id <> to_id)
);
-- One pending proposal out per person
CREATE UNIQUE INDEX uq_cp_proposals_pending_from ON cp_proposals (from_id) WHERE status = 'PENDING';
CREATE INDEX idx_cp_proposals_to ON cp_proposals (to_id) WHERE status = 'PENDING';
CREATE INDEX idx_cp_proposals_age ON cp_proposals (created_at) WHERE status = 'PENDING';

CREATE TABLE cp_couples (
    id                  BIGSERIAL PRIMARY KEY,
    user_a              BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_b              BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    ring_id             VARCHAR(40)  NOT NULL REFERENCES cp_rings (id),
    love                BIGINT       NOT NULL DEFAULT 0 CHECK (love >= 0),
    message             VARCHAR(120),
    since               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    wallpaper_url       VARCHAR(500),
    wallpaper_public_id VARCHAR(300),
    end_request_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    end_request_at      TIMESTAMPTZ,
    ended_at            TIMESTAMPTZ,
    CHECK (user_a <> user_b)
);
CREATE INDEX idx_cp_couples_live_love ON cp_couples (love DESC) WHERE ended_at IS NULL;

-- One row per person in a live CP: the primary key makes "at most one CP each" race-safe
CREATE TABLE cp_members (
    user_id   BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    couple_id BIGINT NOT NULL REFERENCES cp_couples (id) ON DELETE CASCADE
);

-- Ring box, oldest first
CREATE TABLE cp_couple_rings (
    id         BIGSERIAL PRIMARY KEY,
    couple_id  BIGINT      NOT NULL REFERENCES cp_couples (id) ON DELETE CASCADE,
    ring_id    VARCHAR(40) NOT NULL REFERENCES cp_rings (id),
    added_by   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cp_couple_rings ON cp_couple_rings (couple_id, id);

CREATE TABLE cp_blessings (
    id         BIGSERIAL PRIMARY KEY,
    couple_id  BIGINT      NOT NULL REFERENCES cp_couples (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    love       INTEGER     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cp_blessings_couple ON cp_blessings (couple_id, id DESC);
CREATE INDEX idx_cp_blessings_daily ON cp_blessings (couple_id, user_id, created_at);

-- Love gained per couple per day (India time), for the Couple ranking
CREATE TABLE cp_love_daily (
    couple_id BIGINT NOT NULL REFERENCES cp_couples (id) ON DELETE CASCADE,
    day       DATE   NOT NULL,
    love      BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (couple_id, day)
);
CREATE INDEX idx_cp_love_daily_day ON cp_love_daily (day);

-- Retried coin-spending requests (Idempotency-Key) answer with the first result
CREATE TABLE cp_idempotency (
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key        VARCHAR(80)  NOT NULL,
    response   TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)
);
