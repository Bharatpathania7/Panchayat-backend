-- Switches the admin can flip for each game mode (a game that is not listed is on).
CREATE TABLE IF NOT EXISTS game_settings (
    game    VARCHAR(20) PRIMARY KEY,
    enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
