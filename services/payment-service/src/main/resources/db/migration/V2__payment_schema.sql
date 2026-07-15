-- V2__payment_schema.sql  (schema = payment_service)

CREATE TABLE payment_transactions (
    id                     UUID         PRIMARY KEY,
    order_id               UUID         NOT NULL,
    user_id                UUID         NOT NULL,
    amount                 BIGINT       NOT NULL CHECK (amount >= 0),
    currency               VARCHAR(3)   NOT NULL DEFAULT 'VND',
    idempotency_key        VARCHAR(100) NOT NULL,
    gateway_order_id       VARCHAR(100),
    gateway_transaction_id VARCHAR(100),
    status                 VARCHAR(30)  NOT NULL DEFAULT 'INITIATED',
    gateway_response       JSONB,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_idempotency  UNIQUE (idempotency_key),
    CONSTRAINT uq_payment_gateway_txn  UNIQUE (gateway_transaction_id),
    CONSTRAINT chk_payment_status CHECK (status IN
        ('INITIATED','PENDING','SUCCESS','FAILED','REFUNDED'))
);
CREATE INDEX idx_payment_order  ON payment_transactions (order_id);
CREATE INDEX idx_payment_status ON payment_transactions (status);

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
