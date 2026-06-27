-- V4__order_refund_states.sql  (schema = order_service)
-- Add REFUND_PENDING + REFUND_MANUAL_REVIEW to order status CHECK (refund leg, INSPECT-2b mảnh 1).
-- Drop+recreate chk_order_status; never edit applied V2. status column length=20 fits
-- 'REFUND_MANUAL_REVIEW' (20 chars) exactly — no column ALTER needed.

ALTER TABLE orders DROP CONSTRAINT chk_order_status;
ALTER TABLE orders ADD CONSTRAINT chk_order_status CHECK (status IN
    ('CREATED','RESERVED','PAYMENT_PENDING','PAID','PAYMENT_FAILED','EXPIRED','CANCELLED',
     'REFUNDED','REFUND_PENDING','REFUND_MANUAL_REVIEW'));
