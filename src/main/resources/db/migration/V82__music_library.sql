-- Room music library: songs people upload (live for everyone at once) and the admin's view of them.
CREATE TABLE music_songs (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(80)  NOT NULL,
    artist      VARCHAR(80)  NOT NULL DEFAULT 'Unknown artist',
    duration    INT          NOT NULL DEFAULT 0 CHECK (duration >= 0),
    file_url    VARCHAR(500) NOT NULL,
    size_bytes  BIGINT       NOT NULL DEFAULT 0,
    uploader_id BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status      VARCHAR(8)   NOT NULL DEFAULT 'LIVE' CHECK (status IN ('LIVE', 'HIDDEN')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_music_songs_live ON music_songs (status, id DESC);
CREATE INDEX idx_music_songs_uploader ON music_songs (uploader_id, created_at DESC);
