CREATE TABLE ranking_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    reset_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO ranking_settings (id, reset_at)
VALUES (1, now());
