-- V5: Add processed_messages table and ai_introduction_updated_at column
-- Author: Antigravity
-- Date: 2026-06-23

CREATE TABLE processed_messages (
    message_id VARCHAR(255) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE concerts ADD COLUMN ai_introduction_updated_at TIMESTAMPTZ;
