-- Who got the free VIP trial for new users (so the admin panel can show it). One row per person.
CREATE TABLE vip_trials (
    user_id    BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    days       INT         NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_vip_trials_granted ON vip_trials (granted_at DESC);

-- People who already got the trial before this table existed: a VIP that ends 2 to 4 days after they joined was the free trial.
INSERT INTO vip_trials (user_id, days, granted_at, ends_at)
SELECT id, GREATEST(1, ROUND(EXTRACT(EPOCH FROM (vip_expires_at - created_at)) / 86400)::int), created_at, vip_expires_at
FROM users
WHERE role = 'USER' AND vip_expires_at IS NOT NULL
  AND vip_expires_at BETWEEN created_at + interval '2 days' AND created_at + interval '4 days'
ON CONFLICT DO NOTHING;
