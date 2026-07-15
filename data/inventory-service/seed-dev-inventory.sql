-- Dev seed data for inventory-service.
-- Mirrors DevSeedService. Existing rows are never topped up or reset.

CREATE SCHEMA IF NOT EXISTS inventory_service;
SET search_path TO inventory_service, public;

BEGIN;

CREATE TEMP TABLE _inventory_seed (
    id UUID PRIMARY KEY,
    concert_id UUID NOT NULL,
    name VARCHAR(32) NOT NULL,
    price INTEGER NOT NULL,
    total_qty INTEGER NOT NULL,
    per_user_limit INTEGER NOT NULL,
    sale_start_at TIMESTAMPTZ NOT NULL,
    sale_end_at TIMESTAMPTZ NOT NULL
) ON COMMIT DROP;

INSERT INTO _inventory_seed (id, concert_id, name, price, total_qty, per_user_limit, sale_start_at, sale_end_at) VALUES
    ('22222222-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'SVIP', 5000000, 50, 4, now() - interval '1 day', now() + interval '365 days'),
    ('22222222-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'VIP', 3000000, 100, 4, now() - interval '1 day', now() + interval '365 days'),
    ('22222222-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'CAT1', 1500000, 200, 4, now() - interval '1 day', now() + interval '365 days'),
    ('22222222-0000-4000-8000-000000000004', '11111111-1111-4111-8111-111111111111', 'CAT2', 1000000, 300, 4, now() - interval '1 day', now() + interval '365 days'),
    ('22222222-0000-4000-8000-000000000005', '11111111-1111-4111-8111-111111111111', 'GA', 500000, 500, 4, now() - interval '1 day', now() + interval '365 days'),
    ('22222222-0000-4000-8000-0000000000a1', '11111111-1111-4111-8111-111111111111', 'LOWSTOCK-C1', 500000, 1, 2, now() - interval '1 day', now() + interval '365 days'),
    ('22222222-0000-4000-8000-0000000000a2', '11111111-1111-4111-8111-111111111111', 'LOWSTOCK-C5', 500000, 1, 1, now() - interval '1 day', now() + interval '365 days'),
    ('22222222-0000-4000-8000-0000000000a3', '11111111-1111-4111-8111-111111111111', 'LIMIT-C2', 500000, 10, 2, now() - interval '1 day', now() + interval '365 days'),

    ('dddd0001-0000-4000-8000-000000000001', 'c1c1c1c1-0000-4000-8000-000000000001', 'SVIP', 3500000, 1, 1, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0001-0000-4000-8000-000000000002', 'c1c1c1c1-0000-4000-8000-000000000001', 'VIP', 2300000, 2, 4, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0001-0000-4000-8000-000000000003', 'c1c1c1c1-0000-4000-8000-000000000001', 'CAT1', 1600000, 2, 4, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0001-0000-4000-8000-000000000004', 'c1c1c1c1-0000-4000-8000-000000000001', 'CAT2', 980000, 10, 2, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0001-0000-4000-8000-000000000005', 'c1c1c1c1-0000-4000-8000-000000000001', 'GA', 650000, 1, 2, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0002-0000-4000-8000-000000000001', 'c1c1c1c1-0000-4000-8000-000000000002', 'SVIP', 3500000, 1, 1, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0002-0000-4000-8000-000000000002', 'c1c1c1c1-0000-4000-8000-000000000002', 'VIP', 2300000, 2, 4, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0002-0000-4000-8000-000000000003', 'c1c1c1c1-0000-4000-8000-000000000002', 'CAT1', 1600000, 2, 4, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0002-0000-4000-8000-000000000004', 'c1c1c1c1-0000-4000-8000-000000000002', 'CAT2', 980000, 10, 2, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0002-0000-4000-8000-000000000005', 'c1c1c1c1-0000-4000-8000-000000000002', 'GA', 650000, 1, 2, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0003-0000-4000-8000-000000000001', 'c1c1c1c1-0000-4000-8000-000000000003', 'SVIP', 3500000, 1, 1, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0003-0000-4000-8000-000000000002', 'c1c1c1c1-0000-4000-8000-000000000003', 'VIP', 2300000, 2, 4, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0003-0000-4000-8000-000000000003', 'c1c1c1c1-0000-4000-8000-000000000003', 'CAT1', 1600000, 2, 4, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0003-0000-4000-8000-000000000004', 'c1c1c1c1-0000-4000-8000-000000000003', 'CAT2', 980000, 10, 2, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0003-0000-4000-8000-000000000005', 'c1c1c1c1-0000-4000-8000-000000000003', 'GA', 650000, 1, 2, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0004-0000-4000-8000-000000000001', 'c1c1c1c1-0000-4000-8000-000000000004', 'SVIP', 3500000, 1, 1, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0004-0000-4000-8000-000000000002', 'c1c1c1c1-0000-4000-8000-000000000004', 'VIP', 2300000, 2, 4, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0004-0000-4000-8000-000000000003', 'c1c1c1c1-0000-4000-8000-000000000004', 'CAT1', 1600000, 2, 4, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0004-0000-4000-8000-000000000004', 'c1c1c1c1-0000-4000-8000-000000000004', 'CAT2', 980000, 10, 2, now() - interval '1 day', now() + interval '365 days'),
    ('dddd0004-0000-4000-8000-000000000005', 'c1c1c1c1-0000-4000-8000-000000000004', 'GA', 650000, 1, 2, now() - interval '1 day', now() + interval '365 days'),

    ('dddd0005-0000-4000-8000-000000000001', 'c1c1c1c1-0000-4000-8000-000000000005', 'SVIP', 3500000, 20, 4, '2026-08-01T00:00:00Z', '2027-06-30T00:00:00Z'),
    ('dddd0005-0000-4000-8000-000000000002', 'c1c1c1c1-0000-4000-8000-000000000005', 'VIP', 2300000, 20, 4, '2026-08-01T00:00:00Z', '2027-06-30T00:00:00Z'),
    ('dddd0005-0000-4000-8000-000000000003', 'c1c1c1c1-0000-4000-8000-000000000005', 'CAT1', 1600000, 20, 4, '2026-08-01T00:00:00Z', '2027-06-30T00:00:00Z'),
    ('dddd0005-0000-4000-8000-000000000004', 'c1c1c1c1-0000-4000-8000-000000000005', 'CAT2', 980000, 20, 4, '2026-08-01T00:00:00Z', '2027-06-30T00:00:00Z'),
    ('dddd0005-0000-4000-8000-000000000005', 'c1c1c1c1-0000-4000-8000-000000000005', 'GA', 650000, 20, 4, '2026-08-01T00:00:00Z', '2027-06-30T00:00:00Z'),
    ('dddd0006-0000-4000-8000-000000000001', 'c1c1c1c1-0000-4000-8000-000000000006', 'SVIP', 3500000, 20, 4, '2026-06-01T00:00:00Z', '2026-06-30T00:00:00Z'),
    ('dddd0006-0000-4000-8000-000000000002', 'c1c1c1c1-0000-4000-8000-000000000006', 'VIP', 2300000, 20, 4, '2026-06-01T00:00:00Z', '2026-06-30T00:00:00Z'),
    ('dddd0006-0000-4000-8000-000000000003', 'c1c1c1c1-0000-4000-8000-000000000006', 'CAT1', 1600000, 20, 4, '2026-06-01T00:00:00Z', '2026-06-30T00:00:00Z'),
    ('dddd0006-0000-4000-8000-000000000004', 'c1c1c1c1-0000-4000-8000-000000000006', 'CAT2', 980000, 20, 4, '2026-06-01T00:00:00Z', '2026-06-30T00:00:00Z'),
    ('dddd0006-0000-4000-8000-000000000005', 'c1c1c1c1-0000-4000-8000-000000000006', 'GA', 650000, 20, 4, '2026-06-01T00:00:00Z', '2026-06-30T00:00:00Z');

INSERT INTO ticket_types (
    id, concert_id, name, price, per_user_limit,
    sale_start_at, sale_end_at, concert_cancelled, created_at, updated_at
)
SELECT
    id, concert_id, name, price, per_user_limit,
    sale_start_at, sale_end_at, FALSE, now(), now()
FROM _inventory_seed
ON CONFLICT (id) DO NOTHING;

INSERT INTO ticket_type_inventory (ticket_type_id, total_qty, sold_qty, reserved_qty)
SELECT id, total_qty, 0, 0
FROM _inventory_seed
ON CONFLICT (ticket_type_id) DO NOTHING;

COMMIT;
