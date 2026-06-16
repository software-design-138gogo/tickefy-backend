CREATE TABLE IF NOT EXISTS tickets
(
    id            UUID         NOT NULL PRIMARY KEY,
    order_id      VARCHAR(255) NOT NULL,
    order_item_id VARCHAR(255) NOT NULL,
    user_id       VARCHAR(255) NOT NULL,
    event_id      VARCHAR(255) NOT NULL,
    ticket_type_id VARCHAR(255),
    zone_id       VARCHAR(255),
    ticket_name   VARCHAR(500),
    status        VARCHAR(50)  NOT NULL DEFAULT 'ISSUED',
    qr_token      VARCHAR(255) NOT NULL,
    checked_in_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_tickets_order_item_id UNIQUE (order_item_id),
    CONSTRAINT uq_tickets_qr_token      UNIQUE (qr_token)
);

CREATE INDEX IF NOT EXISTS idx_tickets_user_id   ON tickets (user_id);
CREATE INDEX IF NOT EXISTS idx_tickets_event_id  ON tickets (event_id);
CREATE INDEX IF NOT EXISTS idx_tickets_qr_token  ON tickets (qr_token);
CREATE INDEX IF NOT EXISTS idx_tickets_status    ON tickets (status);
