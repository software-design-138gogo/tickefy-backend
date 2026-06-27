-- V5__create_refund_jobs.sql  (schema = order_service)

CREATE TABLE refund_jobs (
    concert_id UUID NOT NULL PRIMARY KEY,
    enabled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED'
);
