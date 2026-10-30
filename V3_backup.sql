
CREATE TABLE user_favorite_games (
    user_id BIGINT NOT NULL,
    game VARCHAR(30) NOT NULL,
    game_order INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT fk_user_favorite_games_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_user_favorite_games_order
        UNIQUE (user_id, game_order),

    CONSTRAINT uq_user_favorite_games_game
        UNIQUE (user_id, game)
);

CREATE INDEX idx_user_favorite_games_user_id
    ON user_favorite_games(user_id);
