-- The "Special events" cards on the Discover screen, managed from the admin panel.
CREATE TABLE special_events (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(80)  NOT NULL,
    when_text  VARCHAR(80)  NOT NULL DEFAULT '',
    body       VARCHAR(240) NOT NULL DEFAULT '',
    theme      VARCHAR(16)  NOT NULL DEFAULT 'gold',
    motif      VARCHAR(16)  NOT NULL DEFAULT 'lanterns',
    image_url  VARCHAR(500),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order INTEGER      NOT NULL DEFAULT 0,
    starts_at  TIMESTAMPTZ,
    ends_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
INSERT INTO special_events (title, when_text, body, theme, motif, sort_order) VALUES
 ('Diwali Mehfil Week', 'Starts in 3 days', 'Themed rooms, diya decorations and a festive gift wall.', 'gold', 'lanterns', 1),
 ('Ludo Premier League', 'Live now', 'Team up with your room. Top rooms get a gold badge.', 'ember', 'kites', 2),
 ('Open Mic Saturdays', 'Every Saturday, 9 PM', 'Five-minute sets in voice rooms. Be kind, clap loud.', 'night', 'river', 3);
