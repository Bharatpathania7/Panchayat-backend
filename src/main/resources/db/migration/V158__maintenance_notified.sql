-- Who got the "we're briefly closing for maintenance" email, so the "Panchayat is live again" email goes to exactly them
-- (and nobody else) when maintenance ends. Kept in the database because the backend restarts during maintenance (deploy).
CREATE TABLE IF NOT EXISTS maintenance_notified (
    email       VARCHAR(320) PRIMARY KEY,
    notified_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
