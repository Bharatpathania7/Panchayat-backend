-- Ludo tables share room_games with Jackaroo: one open table per game type in a room.
DROP INDEX IF EXISTS uq_room_games_open;
CREATE UNIQUE INDEX uq_room_games_open ON room_games (room_id, game) WHERE status IN ('WAITING', 'PLAYING');
