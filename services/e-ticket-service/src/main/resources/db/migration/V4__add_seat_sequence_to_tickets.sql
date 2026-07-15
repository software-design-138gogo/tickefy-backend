-- qty>1: one order_item with quantity=N issues N tickets.
-- Idempotency key changes from (order_item_id) to (order_item_id, seat_sequence).
-- NEVER modify earlier migrations (already applied). Existing rows default seat_sequence=1
-- so the old single-ticket rows stay unique under the new composite constraint.

ALTER TABLE tickets ADD COLUMN seat_sequence INTEGER NOT NULL DEFAULT 1;

ALTER TABLE tickets DROP CONSTRAINT uq_tickets_order_item_id;

ALTER TABLE tickets ADD CONSTRAINT uq_tickets_order_item_seq UNIQUE (order_item_id, seat_sequence);
