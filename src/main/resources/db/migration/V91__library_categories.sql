-- Parivar library: the admin makes the categories (Badges, Frames, Chest art, ...) and files every picture or animation in one.
CREATE TABLE parivar_library_categories (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(40) NOT NULL,
    sort_order INT         NOT NULL DEFAULT 0,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_library_category_name ON parivar_library_categories (lower(name));
INSERT INTO parivar_library_categories (name, sort_order) VALUES ('Badges', 1), ('Animations', 2), ('Gifts', 3), ('Titles', 4), ('Chest art', 5), ('Parivar art', 6);

ALTER TABLE parivar_collectibles ADD COLUMN category_id BIGINT REFERENCES parivar_library_categories (id) ON DELETE RESTRICT;
UPDATE parivar_collectibles c SET category_id = (SELECT id FROM parivar_library_categories k WHERE k.name = CASE c.kind WHEN 'BADGE' THEN 'Badges' WHEN 'ANIMATION' THEN 'Animations' WHEN 'GIFT' THEN 'Gifts' ELSE 'Titles' END);
ALTER TABLE parivar_collectibles DROP CONSTRAINT IF EXISTS parivar_collectibles_kind_check;
ALTER TABLE parivar_collectibles ALTER COLUMN kind SET DEFAULT 'ITEM';
ALTER TABLE parivar_collectibles ALTER COLUMN kind TYPE VARCHAR(12);

ALTER TABLE user_collection DROP CONSTRAINT IF EXISTS user_collection_kind_check;
ALTER TABLE user_collection ADD CONSTRAINT user_collection_kind_check CHECK (kind IN ('TITLE', 'BADGE', 'ANIMATION', 'GIFT', 'ITEM'));
ALTER TABLE user_collection ADD COLUMN category VARCHAR(40);
UPDATE user_collection SET category = CASE kind WHEN 'BADGE' THEN 'Badges' WHEN 'ANIMATION' THEN 'Animations' WHEN 'GIFT' THEN 'Gifts' ELSE 'Titles' END;
