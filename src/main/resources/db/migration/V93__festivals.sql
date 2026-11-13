-- Festival Manager: a festival has one editable DRAFT and any number of published versions (never changed once published).
-- The user app shows the festival whose published version is on and inside its start and end time. Rolling back picks an older version.
CREATE TABLE festivals (
    id                   BIGSERIAL PRIMARY KEY,
    name                 VARCHAR(60) NOT NULL,
    enabled              BOOLEAN     NOT NULL DEFAULT FALSE,
    starts_at            TIMESTAMPTZ,
    ends_at              TIMESTAMPTZ,
    published_version_id BIGINT,
    created_by           VARCHAR(60),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE festival_versions (
    id           BIGSERIAL PRIMARY KEY,
    festival_id  BIGINT      NOT NULL REFERENCES festivals (id) ON DELETE CASCADE,
    kind         VARCHAR(10) NOT NULL CHECK (kind IN ('DRAFT', 'PUBLISHED')),
    version      INT         NOT NULL DEFAULT 0,
    config       TEXT        NOT NULL,
    note         VARCHAR(200),
    created_by   VARCHAR(60),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_festival_one_draft ON festival_versions (festival_id) WHERE kind = 'DRAFT';
CREATE UNIQUE INDEX uq_festival_version_no ON festival_versions (festival_id, version) WHERE kind = 'PUBLISHED';
CREATE TABLE festival_audit (
    id          BIGSERIAL PRIMARY KEY,
    festival_id BIGINT,
    action      VARCHAR(20) NOT NULL,
    detail      VARCHAR(300),
    admin_name  VARCHAR(60),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
