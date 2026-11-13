-- Profile titles, dynamic: how long a title lasts, who may buy it and when, and automatic rules that give it to the right people.
ALTER TABLE profile_titles ADD COLUMN duration_days INT         NOT NULL DEFAULT 0 CHECK (duration_days >= 0);
ALTER TABLE profile_titles ADD COLUMN for_sale      BOOLEAN     NOT NULL DEFAULT TRUE;
ALTER TABLE profile_titles ADD COLUMN sale_from     TIMESTAMPTZ;
ALTER TABLE profile_titles ADD COLUMN sale_until    TIMESTAMPTZ;
ALTER TABLE profile_titles ADD COLUMN min_vip_level INT         NOT NULL DEFAULT 0 CHECK (min_vip_level >= 0);
ALTER TABLE profile_titles ADD COLUMN parivar_only  BOOLEAN     NOT NULL DEFAULT FALSE;

-- Automatic rules. IDS: a list of 7-digit user IDs (a rare people list, the team ...). VIP_LEVEL: everyone with at least that VIP level.
-- PARIVAR_ROLE: everyone who is LEADER / DEPUTY / ADMIN / MEMBER of a Parivar.
-- days = 0: they hold the title while they qualify and lose it when they stop. days > 0: a one-time gift of that many days.
CREATE TABLE title_auto_rules (
    id         BIGSERIAL PRIMARY KEY,
    title_id   BIGINT       NOT NULL REFERENCES profile_titles (id) ON DELETE CASCADE,
    kind       VARCHAR(14)  NOT NULL CHECK (kind IN ('IDS', 'VIP_LEVEL', 'PARIVAR_ROLE')),
    value      VARCHAR(4000) NOT NULL,
    days       INT          NOT NULL DEFAULT 0 CHECK (days >= 0),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    matched    INT          NOT NULL DEFAULT 0,
    last_run   TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_title_rules_title ON title_auto_rules (title_id);
-- Who a one-time rule already gave the title to (so an expired gift is not given again)
CREATE TABLE title_rule_grants (
    rule_id BIGINT NOT NULL REFERENCES title_auto_rules (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (rule_id, user_id)
);
