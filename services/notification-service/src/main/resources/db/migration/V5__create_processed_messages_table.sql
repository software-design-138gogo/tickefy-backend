-- Dedup ledger (F1): each (message_id, event_type) processed exactly once. State-guard idempotency.
-- BARE table name (mirror V4) -- Flyway default-schema set search_path to the service schema.
-- No bare placeholder syntax in this file (Flyway placeholder trap).
CREATE TABLE processed_messages (
    message_id   UUID         NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_messages PRIMARY KEY (message_id, event_type)
);
