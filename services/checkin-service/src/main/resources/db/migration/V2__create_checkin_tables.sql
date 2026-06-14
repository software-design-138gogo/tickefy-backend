-- checkin_schema: V2 - core checkin tables

CREATE TABLE IF NOT EXISTS checkin_events
(
    id              UUID         NOT NULL PRIMARY KEY,
    ticket_id       VARCHAR(255),
    qr_token_masked VARCHAR(20)  NOT NULL,
    concert_id      VARCHAR(255) NOT NULL,
    staff_id        VARCHAR(255) NOT NULL,
    device_id       VARCHAR(255) NOT NULL,
    gate            VARCHAR(100),
    result          VARCHAR(50)  NOT NULL,
    is_offline      BOOLEAN      NOT NULL DEFAULT FALSE,
    scanned_at      TIMESTAMPTZ  NOT NULL,
    synced_at       TIMESTAMPTZ,
    sync_batch_id   VARCHAR(255),
    conflict_id     UUID,
    request_id      VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ce_concert_id    ON checkin_events (concert_id);
CREATE INDEX IF NOT EXISTS idx_ce_ticket_id     ON checkin_events (ticket_id);
CREATE INDEX IF NOT EXISTS idx_ce_staff_id      ON checkin_events (staff_id);
CREATE INDEX IF NOT EXISTS idx_ce_device_id     ON checkin_events (device_id);
CREATE INDEX IF NOT EXISTS idx_ce_scanned_at    ON checkin_events (scanned_at);

CREATE TABLE IF NOT EXISTS checkin_snapshots
(
    id           UUID         NOT NULL PRIMARY KEY,
    concert_id   VARCHAR(255) NOT NULL,
    gate         VARCHAR(100),
    generated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    total_count  INT          NOT NULL DEFAULT 0,
    payload      JSONB        NOT NULL DEFAULT '[]',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cs_concert_id ON checkin_snapshots (concert_id);
CREATE INDEX IF NOT EXISTS idx_cs_expires_at ON checkin_snapshots (expires_at);

CREATE TABLE IF NOT EXISTS sync_batches
(
    id            UUID         NOT NULL PRIMARY KEY,
    sync_batch_id VARCHAR(255) NOT NULL,
    device_id     VARCHAR(255) NOT NULL,
    concert_id    VARCHAR(255) NOT NULL,
    gate          VARCHAR(100),
    staff_id      VARCHAR(255) NOT NULL,
    item_count    INT          NOT NULL DEFAULT 0,
    result_payload JSONB,
    processed_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_sync_batches_batch_id UNIQUE (sync_batch_id)
);

CREATE INDEX IF NOT EXISTS idx_sb_device_id    ON sync_batches (device_id);
CREATE INDEX IF NOT EXISTS idx_sb_concert_id   ON sync_batches (concert_id);

CREATE TABLE IF NOT EXISTS conflicts
(
    id              UUID         NOT NULL PRIMARY KEY,
    ticket_id       VARCHAR(255) NOT NULL,
    concert_id      VARCHAR(255) NOT NULL,
    winner_event_id UUID,
    loser_event_id  UUID,
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_conflicts_ticket_id  ON conflicts (ticket_id);
CREATE INDEX IF NOT EXISTS idx_conflicts_concert_id ON conflicts (concert_id);
