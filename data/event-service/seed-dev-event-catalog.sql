-- Dev seed data for event-service.
-- Mirrors DatabaseSeeder and EventAnchorSeeder.

CREATE SCHEMA IF NOT EXISTS event_service;
SET search_path TO event_service, public;

BEGIN;

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TEMP TABLE _event_seed_artists (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    bio TEXT
) ON COMMIT DROP;

INSERT INTO _event_seed_artists (id, name, bio) VALUES
    ('90000000-0000-4000-8000-000000000001', 'HIEUTHUHAI', 'Rapper nổi bật của Việt Nam.'),
    ('90000000-0000-4000-8000-000000000002', 'Isaac', 'Nam ca sĩ với phong cách lịch lãm.'),
    ('90000000-0000-4000-8000-000000000003', 'Ninh Dương Lan Ngọc', 'Ngọc nữ màn ảnh Việt.'),
    ('90000000-0000-4000-8000-000000000004', 'Trang Pháp', 'Ca sĩ, nhạc sĩ kiêm nhà sản xuất âm nhạc tài năng.');

INSERT INTO artists (id, name, bio, created_at, updated_at)
SELECT id, name, bio, now(), now()
FROM _event_seed_artists a
WHERE NOT EXISTS (SELECT 1 FROM artists existing WHERE existing.name = a.name);

INSERT INTO venues (id, name, address, city, capacity, created_at, updated_at) VALUES
    ('aaaaaaaa-0000-4000-8000-000000000001', 'Tickefy Rehearsal Arena', '1 Rehearsal Way', 'TP. Hồ Chí Minh', 25000, now(), now()),
    ('aaaaaaaa-0000-4000-8000-000000000002', 'Sân vận động Quân Khu 7', '202 Hoàng Văn Thụ', 'TP. Hồ Chí Minh', 25000, now(), now()),
    ('aaaaaaaa-0000-4000-8000-000000000003', 'Sân vận động Quốc gia Mỹ Đình', 'Đường Lê Đức Thọ', 'Hà Nội', 40000, now(), now())
ON CONFLICT (id) DO NOTHING;

CREATE TEMP TABLE _event_seed_concerts (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    sale_start_at TIMESTAMPTZ,
    sale_end_at TIMESTAMPTZ,
    event_date TIMESTAMPTZ NOT NULL,
    venue_id UUID NOT NULL
) ON COMMIT DROP;

INSERT INTO _event_seed_concerts (id, title, description, sale_start_at, sale_end_at, event_date, venue_id) VALUES
    ('11111111-1111-4111-8111-111111111111', 'Tickefy Rehearsal Concert', 'E2E baseline anchor concert (dev seed).', now() - interval '1 day', '2027-06-23T00:00:00Z', now() + interval '30 days', 'aaaaaaaa-0000-4000-8000-000000000001'),
    ('c1c1c1c1-0000-4000-8000-000000000001', 'Anh Trai Say Hi Concert 2026', 'Concert quy tụ dàn anh trai cực phẩm.', now() - interval '1 day', now() + interval '365 days', now() + interval '30 days', 'aaaaaaaa-0000-4000-8000-000000000002'),
    ('c1c1c1c1-0000-4000-8000-000000000002', 'Anh Trai Vượt Chông Gai 2026', 'Đêm nhạc bùng nổ của các anh tài.', now() - interval '1 day', now() + interval '365 days', now() + interval '45 days', 'aaaaaaaa-0000-4000-8000-000000000003'),
    ('c1c1c1c1-0000-4000-8000-000000000003', 'Em Xinh Say Hi', 'Đêm diễn tràn ngập sự ngọt ngào.', now() - interval '1 day', now() + interval '365 days', now() + interval '50 days', 'aaaaaaaa-0000-4000-8000-000000000002'),
    ('c1c1c1c1-0000-4000-8000-000000000004', 'Chị Đẹp Đạp Gió Rẽ Sóng', 'Sự kết hợp hoàn hảo của các chị đẹp.', now() - interval '1 day', now() + interval '365 days', now() + interval '60 days', 'aaaaaaaa-0000-4000-8000-000000000003'),
    ('c1c1c1c1-0000-4000-8000-000000000005', 'E2E Sale-NotStarted Concert', 'E2E sale-window test concert (dev seed).', '2026-08-01T00:00:00Z', '2027-06-30T00:00:00Z', '2026-09-15T12:00:00Z', 'aaaaaaaa-0000-4000-8000-000000000002'),
    ('c1c1c1c1-0000-4000-8000-000000000006', 'E2E Sale-Closed Concert', 'E2E sale-window test concert (dev seed).', '2026-06-01T00:00:00Z', '2026-06-30T00:00:00Z', '2026-09-01T12:00:00Z', 'aaaaaaaa-0000-4000-8000-000000000002');

INSERT INTO concerts (
    id, title, description, status, sale_start_at, sale_end_at, event_date,
    venue_id, reminder_sent, created_at, updated_at
)
SELECT
    id, title, description, 'PUBLISHED', sale_start_at, sale_end_at, event_date,
    venue_id, FALSE, now(), now()
FROM _event_seed_concerts
ON CONFLICT (id) DO NOTHING;

CREATE TEMP TABLE _event_seed_zones (
    ticket_type_name VARCHAR(50) NOT NULL,
    zone_name VARCHAR(100) NOT NULL
) ON COMMIT DROP;

INSERT INTO _event_seed_zones (ticket_type_name, zone_name) VALUES
    ('SVIP', 'SVIP Zone'),
    ('VIP', 'VIP Zone'),
    ('CAT1', 'Category 1'),
    ('CAT2', 'Category 2'),
    ('GA', 'General Admission');

INSERT INTO concert_zones (concert_id, ticket_type_name, zone_name, created_at)
SELECT c.id, z.ticket_type_name, z.zone_name, now()
FROM _event_seed_concerts c
CROSS JOIN _event_seed_zones z
WHERE NOT EXISTS (
    SELECT 1
    FROM concert_zones existing
    WHERE existing.concert_id = c.id
      AND existing.ticket_type_name = z.ticket_type_name
);

COMMIT;
