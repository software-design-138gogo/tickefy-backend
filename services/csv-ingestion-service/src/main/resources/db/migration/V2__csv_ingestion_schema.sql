-- V2__csv_ingestion_schema.sql  (schema = csv_ingestion_service)
-- CSV/VIP guest import: jobs, staging, errors, official guests, outbox.

CREATE TABLE import_jobs (
    id                       UUID         PRIMARY KEY,
    concert_id               UUID         NOT NULL,
    organizer_id             UUID         NOT NULL,
    source                   VARCHAR(16)  NOT NULL DEFAULT 'UPLOAD',
    object_key               VARCHAR(512) NOT NULL,
    error_report_object_key  VARCHAR(512),
    status                   VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    total_rows               INTEGER      NOT NULL DEFAULT 0,
    success_rows             INTEGER      NOT NULL DEFAULT 0,
    failed_rows              INTEGER      NOT NULL DEFAULT 0,
    duplicate_rows           INTEGER      NOT NULL DEFAULT 0,
    attempt_count            INTEGER      NOT NULL DEFAULT 0,
    failure_reason           VARCHAR(255),
    message_id               UUID,
    started_at               TIMESTAMPTZ,
    finished_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_import_jobs_source CHECK (source IN ('UPLOAD','CRON')),
    CONSTRAINT chk_import_jobs_status CHECK (status IN
        ('PENDING','PROCESSING','COMPLETED','PARTIALLY_COMPLETED','FAILED'))
);
CREATE INDEX idx_import_jobs_concert ON import_jobs (concert_id);
CREATE INDEX idx_import_jobs_status ON import_jobs (status);

CREATE TABLE vip_guest_staging (
    id               UUID         PRIMARY KEY,
    import_job_id    UUID         NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    concert_id       UUID         NOT NULL,
    email            VARCHAR(320) NOT NULL,
    full_name        VARCHAR(200),
    ticket_type_id   UUID,
    ticket_type_name VARCHAR(100),
    line_number      INTEGER      NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_staging_job_email UNIQUE (import_job_id, email)
);
CREATE INDEX idx_staging_job ON vip_guest_staging (import_job_id);

CREATE TABLE import_errors (
    id            UUID          PRIMARY KEY,
    import_job_id UUID          NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    line_number   INTEGER       NOT NULL,
    raw_data      VARCHAR(1024),
    reason        VARCHAR(64)   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_errors_job ON import_errors (import_job_id);

CREATE TABLE vip_guests (
    id               UUID         PRIMARY KEY,
    concert_id       UUID         NOT NULL,
    email            VARCHAR(320) NOT NULL,
    full_name        VARCHAR(200),
    ticket_type_id   UUID         NOT NULL,
    ticket_type_name VARCHAR(100),
    import_job_id    UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_vip_guests_concert_email UNIQUE (concert_id, email)
);
CREATE INDEX idx_vip_guests_concert ON vip_guests (concert_id);

CREATE TABLE outbox (
    id           UUID         PRIMARY KEY,
    aggregate_id UUID         NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    payload      JSONB        NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','PUBLISHED','FAILED'))
);
CREATE INDEX idx_outbox_pending ON outbox (created_at) WHERE status = 'PENDING';
