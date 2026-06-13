-- V2__order_schema.sql  (schema = order_service)

CREATE TABLE orders (
    id                     UUID         PRIMARY KEY,
    user_id                UUID         NOT NULL,
    concert_id             UUID         NOT NULL,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'CREATED',
    idempotency_key        VARCHAR(100) NOT NULL,
    reservation_id         UUID,
    payment_transaction_id VARCHAR(64),
    payment_url            TEXT,
    total_amount           BIGINT       NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    expires_at             TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_orders_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_order_status CHECK (status IN
        ('CREATED','RESERVED','PAYMENT_PENDING','PAID','PAYMENT_FAILED','EXPIRED','CANCELLED','REFUNDED'))
);
CREATE INDEX idx_orders_user ON orders (user_id);
CREATE INDEX idx_orders_concert ON orders (concert_id);
CREATE INDEX idx_orders_pending_expiry ON orders (expires_at) WHERE status = 'PAYMENT_PENDING';

CREATE TABLE order_items (
    id             UUID    PRIMARY KEY,
    order_id       UUID    NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    ticket_type_id UUID    NOT NULL,
    quantity       INTEGER NOT NULL CHECK (quantity > 0),
    unit_price     BIGINT  NOT NULL CHECK (unit_price >= 0)
);
CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE outbox (
    id           UUID         PRIMARY KEY,
    aggregate_id UUID         NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    payload      JSONB        NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','PUBLISHED','FAILED'))
);
CREATE INDEX idx_outbox_pending ON outbox (created_at) WHERE status = 'PENDING';
