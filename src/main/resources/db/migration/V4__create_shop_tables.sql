
-- Shop categories table
CREATE TABLE shop_categories (
    id BIGSERIAL PRIMARY KEY,

    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    image_url VARCHAR(1000),

    display_order INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_shop_categories_name UNIQUE (name),
    CONSTRAINT chk_shop_categories_display_order
        CHECK (display_order >= 0)
);


-- Shop products table
CREATE TABLE shop_products (
    id BIGSERIAL PRIMARY KEY,

    category_id BIGINT NOT NULL,

    name VARCHAR(150) NOT NULL,
    description VARCHAR(2000),

    price NUMERIC(12, 2) NOT NULL,

    rarity VARCHAR(20) NOT NULL DEFAULT 'COMMON',
    asset_url VARCHAR(1000),

    display_order INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_shop_products_category
        FOREIGN KEY (category_id)
        REFERENCES shop_categories(id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_shop_products_price
        CHECK (price >= 0),

    CONSTRAINT chk_shop_products_rarity
        CHECK (rarity IN ('COMMON', 'RARE', 'EPIC', 'LEGENDARY')),

    CONSTRAINT chk_shop_products_display_order
        CHECK (display_order >= 0)
);


-- Indexes for common queries
CREATE INDEX idx_shop_products_category_id
    ON shop_products(category_id);

CREATE INDEX idx_shop_products_active
    ON shop_products(active);

CREATE INDEX idx_shop_categories_active_order
    ON shop_categories(active, display_order);
