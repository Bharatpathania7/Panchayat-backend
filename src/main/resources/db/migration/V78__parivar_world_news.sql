-- World news: written by the system only (a Parivar levelled up, a chest opened from activity, a Chori that took funds).
CREATE TABLE parivar_world_news (
    id         BIGSERIAL PRIMARY KEY,
    kind       VARCHAR(12) NOT NULL CHECK (kind IN ('LEVEL_UP', 'CHEST', 'ATTACK')),
    parivar_id BIGINT      NOT NULL REFERENCES parivars (id) ON DELETE CASCADE,
    other_id   BIGINT      REFERENCES parivars (id) ON DELETE CASCADE,
    text       VARCHAR(300) NOT NULL,
    amount     BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_parivar_world_news ON parivar_world_news (id DESC);
