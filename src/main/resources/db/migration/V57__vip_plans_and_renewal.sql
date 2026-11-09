-- VIP is bought with PayU. The plans (name, days, price) are set in the admin panel.
CREATE TABLE IF NOT EXISTS vip_plans (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(80)    NOT NULL,
    days            INT            NOT NULL CHECK (days BETWEEN 1 AND 3650),
    price_in_rupees NUMERIC(10, 2) NOT NULL CHECK (price_in_rupees > 0),
    active          BOOLEAN        NOT NULL DEFAULT TRUE,
    sort_order      INT            NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
INSERT INTO vip_plans (name, days, price_in_rupees, sort_order)
SELECT 'VIP · 1 month', 30, 299, 1 WHERE NOT EXISTS (SELECT 1 FROM vip_plans);

-- Free VIP for a new user (days). 0 = no free trial.
CREATE TABLE IF NOT EXISTS vip_settings (
    id              INT PRIMARY KEY CHECK (id = 1),
    free_trial_days INT NOT NULL DEFAULT 3 CHECK (free_trial_days BETWEEN 0 AND 365)
);
INSERT INTO vip_settings (id, free_trial_days) VALUES (1, 3) ON CONFLICT DO NOTHING;

-- Days of VIP a payment bought (a snapshot taken when checkout starts)
ALTER TABLE payment_transactions ADD COLUMN IF NOT EXISTS purchased_vip_days INT;

-- The VIP end date we already told the person about, so "VIP ended" is sent once per end date
ALTER TABLE users ADD COLUMN IF NOT EXISTS vip_expiry_notice_for TIMESTAMPTZ;
UPDATE users SET vip_expiry_notice_for = vip_expires_at WHERE vip_expires_at IS NOT NULL AND vip_expires_at < now();

INSERT INTO sarpanch_templates (key, title, body, action_label, action_url, category) VALUES
 ('VIP_EXPIRED', 'Your VIP has ended', 'Your VIP has ended. Renew to get your frame, badge, entry and VIP rooms back.', 'Renew VIP', '/vip', 'VIP')
ON CONFLICT (key) DO NOTHING;
