-- V3__add_ticket_type_name_to_order_items.sql  (schema = order_service)
-- Capture ticket type display name at reserve time so OrderPaid/TicketsIssued carry it loss-less
-- (FE GET /api/tickets needs the name). Nullable: existing rows predate the column.
-- Width matches source inventory_service.ticket_types.name VARCHAR(32). NEVER edit applied migrations.

ALTER TABLE order_items ADD COLUMN ticket_type_name VARCHAR(32);
