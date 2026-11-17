-- Reports from people about a user, a chat message or a room. Admin reviews them in the Reports page.
CREATE TABLE user_reports (
    id             BIGSERIAL PRIMARY KEY,
    reporter_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_type    VARCHAR(10)  NOT NULL CHECK (target_type IN ('USER', 'MESSAGE', 'ROOM')),
    target_user_id BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    target_ref     VARCHAR(80),
    snippet        VARCHAR(300),
    reason         VARCHAR(20)  NOT NULL CHECK (reason IN ('SPAM', 'ABUSE', 'HARASSMENT', 'NUDITY', 'HATE', 'SCAM', 'UNDERAGE', 'OTHER')),
    note           VARCHAR(500),
    status         VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    handled_by     VARCHAR(80),
    handled_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_reports_status ON user_reports (status, created_at DESC);
CREATE INDEX idx_user_reports_target ON user_reports (target_user_id);
CREATE INDEX idx_user_reports_reporter_day ON user_reports (reporter_id, created_at);
