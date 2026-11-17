-- Mic-seat relationship tags are now a catalog the admin edits: name, colours, picture and animation, and new ones can be added.
CREATE TABLE IF NOT EXISTS tag_types (
    id            VARCHAR(20) PRIMARY KEY,
    label         VARCHAR(40) NOT NULL,
    one_label     VARCHAR(30) NOT NULL,
    emoji         VARCHAR(16) NOT NULL DEFAULT '🏷️',
    tint          VARCHAR(9)  NOT NULL DEFAULT '#ffb3cf',
    deep          VARCHAR(9)  NOT NULL DEFAULT '#b0245a',
    image_url     VARCHAR(500),
    animation_url VARCHAR(500),
    sort_order    INT NOT NULL DEFAULT 0,
    active        BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO tag_types (id, label, one_label, emoji, tint, deep, sort_order) VALUES
    ('brosis',     'Bro and sis', 'Bro/Sis',    '🍀', '#5fe0c6', '#127a6b', 1),
    ('besties',    'Besties',     'Bestie',     '🎀', '#ff7fa8', '#b0245a', 2),
    ('bro',        'Brothers',    'Bro',        '✊🏻', '#7fb6ff', '#2251b5', 3),
    ('confidant',  'Confidant',   'Confidant',  '🧩', '#b48bff', '#5b2bb5', 4),
    ('apprentice', 'Apprentice',  'Apprentice', '🛡️', '#f2c063', '#a3651a', 5),
    ('sibling',    'Sibling',     'Sibling',    '☘️', '#6fe3a2', '#1d8a52', 6)
ON CONFLICT (id) DO NOTHING;

ALTER TABLE user_tags DROP CONSTRAINT IF EXISTS chk_user_tags_relation;
