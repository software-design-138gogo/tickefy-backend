-- V3: ticket_types.concert_cancelled — Inventory listens to concert.cancelled (CLAUDE §6.3),
-- sets this flag so reserve fails fast (emergency stop-sale). Does NOT alter V1/V2.
-- Unqualified table name matches V2 style; Flyway runs in the inventory_service schema (currentSchema).
ALTER TABLE ticket_types
    ADD COLUMN concert_cancelled BOOLEAN NOT NULL DEFAULT false;
