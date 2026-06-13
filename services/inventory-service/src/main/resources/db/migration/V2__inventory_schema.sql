-- V2__inventory_schema.sql
-- Schema set via flyway default-schema = inventory_service

-- ticket_types: catalog/config for one ticket type of one concert
CREATE TABLE ticket_types (
    id             UUID         PRIMARY KEY,
    concert_id     UUID         NOT NULL,
    name           VARCHAR(32)  NOT NULL,
    price          INTEGER      NOT NULL CHECK (price >= 0),
    per_user_limit INTEGER,
    sale_start_at  TIMESTAMPTZ  NOT NULL,
    sale_end_at    TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_sale_window CHECK (sale_end_at > sale_start_at),
    CONSTRAINT chk_per_user_limit CHECK (per_user_limit IS NULL OR per_user_limit > 0)
);
CREATE INDEX idx_ticket_types_concert ON ticket_types (concert_id);

-- ticket_type_inventory: live counters (source of truth)
CREATE TABLE ticket_type_inventory (
    ticket_type_id UUID      PRIMARY KEY REFERENCES ticket_types(id),
    total_qty      INTEGER   NOT NULL CHECK (total_qty > 0),
    sold_qty       INTEGER   NOT NULL DEFAULT 0 CHECK (sold_qty >= 0),
    reserved_qty   INTEGER   NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0),
    CONSTRAINT chk_no_oversell CHECK (sold_qty + reserved_qty <= total_qty)
);

-- ticket_reservations: audit + per-user source of truth
CREATE TABLE ticket_reservations (
    id             UUID         PRIMARY KEY,
    ticket_type_id UUID         NOT NULL REFERENCES ticket_types(id),
    user_id        UUID         NOT NULL,
    quantity       INTEGER      NOT NULL CHECK (quantity > 0),
    status         VARCHAR(20)  NOT NULL DEFAULT 'RESERVED',
    order_id       UUID         NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_res_status CHECK (status IN ('RESERVED','COMMITTED','RELEASED')),
    CONSTRAINT uq_res_order_type UNIQUE (order_id, ticket_type_id)
);
CREATE INDEX idx_res_active ON ticket_reservations (expires_at) WHERE status = 'RESERVED';
CREATE INDEX idx_res_user_type ON ticket_reservations (user_id, ticket_type_id, status);
