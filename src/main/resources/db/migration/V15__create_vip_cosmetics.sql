CREATE TABLE vip_cosmetics (
    id BIGSERIAL PRIMARY KEY,
    vip_level INTEGER NOT NULL CHECK (vip_level BETWEEN 1 AND 7),
    cosmetic_type VARCHAR(20) NOT NULL
        CHECK (cosmetic_type IN ('FRAME', 'BUBBLE', 'ENTRY', 'BADGE')),
    format VARCHAR(10) NOT NULL
        CHECK (format IN ('SVGA', 'PNG')),
    public_id VARCHAR(255) NOT NULL UNIQUE,
    secure_url TEXT NOT NULL,
    original_filename VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_vip_cosmetic_level_type_format
        UNIQUE (vip_level, cosmetic_type, format),

    CONSTRAINT chk_vip_cosmetic_format
        CHECK (
            (format = 'SVGA')
            OR (format = 'PNG' AND cosmetic_type = 'BADGE')
        )
);

CREATE INDEX idx_vip_cosmetics_level
    ON vip_cosmetics (vip_level);