-- Gate A2: rename event_id -> concert_id in tickets table
-- Renames column and updates the index accordingly.
-- NEVER modify V2__create_tickets_table.sql (already applied).

ALTER TABLE tickets RENAME COLUMN event_id TO concert_id;

DROP INDEX IF EXISTS idx_tickets_event_id;
CREATE INDEX IF NOT EXISTS idx_tickets_concert_id ON tickets (concert_id);
