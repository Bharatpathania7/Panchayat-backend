-- Which VIP level's frame/bubble/entry/badge fit a Spend-reward effect's file is drawn into
-- (the "resolution" picker in the admin panel), since the uploaded file carries no layout itself.
-- 1 keeps today's behaviour (every admin-granted effect uses the VIP 1 fit) for existing rows.
ALTER TABLE reward_rule_items ADD COLUMN IF NOT EXISTS layout_level INTEGER NOT NULL DEFAULT 1 CHECK (layout_level BETWEEN 1 AND 11);
ALTER TABLE user_rewards ADD COLUMN IF NOT EXISTS layout_level INTEGER NOT NULL DEFAULT 1 CHECK (layout_level BETWEEN 1 AND 11);
