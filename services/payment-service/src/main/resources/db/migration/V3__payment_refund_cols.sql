-- V3__payment_refund_cols.sql  (schema = payment_service)
-- Refund leg (mảnh [3]): track the single refund per transaction. refund_request_id is the
-- order-side idempotency key ("refund-"+orderId); UNIQUE prevents double-refund (§10.4).
-- Nullable so existing rows stay valid (ddl-auto=validate). Postgres UNIQUE allows many NULLs.

ALTER TABLE payment_transactions
    ADD COLUMN refund_request_id   VARCHAR(100),
    ADD COLUMN refunded_at         TIMESTAMPTZ,
    ADD COLUMN refund_gateway_ref  VARCHAR(100);

ALTER TABLE payment_transactions
    ADD CONSTRAINT uq_payment_refund_request UNIQUE (refund_request_id);
