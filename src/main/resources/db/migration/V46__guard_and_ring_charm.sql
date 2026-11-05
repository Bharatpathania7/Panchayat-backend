-- Charm that rings carried (a ring gives the person who receives it charm, like a gift does).
CREATE TABLE cp_ring_charm (
    id         BIGSERIAL PRIMARY KEY,
    from_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    to_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ring_id    VARCHAR(40) NOT NULL,
    charm      BIGINT NOT NULL CHECK (charm >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cp_ring_charm_to ON cp_ring_charm (to_id);

-- Guard: people protect each other, once a day per pair.
CREATE TABLE user_protects (
    guard_id   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day        DATE   NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (guard_id, user_id, day),
    CHECK (guard_id <> user_id)
);
CREATE INDEX idx_user_protects_user ON user_protects (user_id);
