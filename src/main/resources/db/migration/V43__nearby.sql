-- Nearby: people who chose to share a rough location (rounded to about 1 km).
ALTER TABLE users ADD COLUMN IF NOT EXISTS nearby_on  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS geo_lat    DOUBLE PRECISION;
ALTER TABLE users ADD COLUMN IF NOT EXISTS geo_lng    DOUBLE PRECISION;
ALTER TABLE users ADD COLUMN IF NOT EXISTS geo_at     TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_users_nearby ON users (geo_lat, geo_lng) WHERE nearby_on;
