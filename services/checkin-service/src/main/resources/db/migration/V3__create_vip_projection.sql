-- checkin_service: V3 - VIP guest projection (cached from csv-ingestion /internal) + freshness meta
CREATE TABLE IF NOT EXISTS vip_guest_projection (
    id                UUID         PRIMARY KEY,
    concert_id        UUID         NOT NULL,
    email             VARCHAR(320) NOT NULL,
    full_name         VARCHAR(200),
    ticket_type_id    UUID,
    ticket_type_name  VARCHAR(100),
    source_message_id UUID,
    cached_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_vip_projection_concert_email UNIQUE (concert_id, email)
);
CREATE INDEX IF NOT EXISTS idx_vip_projection_concert ON vip_guest_projection (concert_id);

CREATE TABLE IF NOT EXISTS vip_cache_meta (
    concert_id        UUID         PRIMARY KEY,
    last_refreshed_at TIMESTAMPTZ  NOT NULL,
    state             VARCHAR(16)  NOT NULL DEFAULT 'FRESH'
);
