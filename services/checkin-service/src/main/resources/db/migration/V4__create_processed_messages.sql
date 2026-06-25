-- checkin_service: V4 - processed_messages (event dedup by messageId, §6.9 / spec §3)
CREATE TABLE IF NOT EXISTS processed_messages (
    message_id   UUID         PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
