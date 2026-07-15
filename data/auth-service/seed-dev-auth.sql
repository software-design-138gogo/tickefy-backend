-- Dev seed data for auth-service.
-- Mirrors TestCustomerSeeder. AdminBootstrapRunner is intentionally env-driven,
-- so it is not represented here.
--
-- Plaintext dev passwords:
--   e2e.customer1@tickefy.local / Customer@12345
--   e2e.customer2@tickefy.local / Customer@12345
--   tanhiep24135@gmail.com      / Hiep@12345
--   hiepvip22@gmail.com         / Hiep@12345

CREATE SCHEMA IF NOT EXISTS auth_service;
SET search_path TO auth_service, public;

BEGIN;

INSERT INTO roles (id, code) VALUES
    (1, 'AUDIENCE'),
    (2, 'ORGANIZER'),
    (3, 'CHECKIN_STAFF'),
    (4, 'ADMIN')
ON CONFLICT (id) DO NOTHING;

CREATE TEMP TABLE _auth_seed_accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    role_code VARCHAR(32) NOT NULL
) ON COMMIT DROP;

INSERT INTO _auth_seed_accounts (id, email, password_hash, full_name, role_code) VALUES
    ('91000000-0000-4000-8000-000000000001', 'e2e.customer1@tickefy.local', '$2a$10$MhX3iESpsOHn0XhYmmeBgemR1Q7UErjfutXYIz.9CTpLYIZH6x94a', 'E2E Customer 1', 'AUDIENCE'),
    ('91000000-0000-4000-8000-000000000002', 'e2e.customer2@tickefy.local', '$2a$10$MhX3iESpsOHn0XhYmmeBgemR1Q7UErjfutXYIz.9CTpLYIZH6x94a', 'E2E Customer 2', 'AUDIENCE'),
    ('91000000-0000-4000-8000-000000000003', 'tanhiep24135@gmail.com', '$2a$10$kVAcXGa0/BICjrMQO88opu9I4hpHEcN3UGfSY6guo3tTURec5IXVW', 'Tan Hiep Le', 'AUDIENCE'),
    ('91000000-0000-4000-8000-000000000004', 'hiepvip22@gmail.com', '$2a$10$kVAcXGa0/BICjrMQO88opu9I4hpHEcN3UGfSY6guo3tTURec5IXVW', 'Hiep Admin', 'ADMIN');

INSERT INTO users (id, email, password_hash, full_name, enabled, created_at, updated_at)
SELECT id, email, password_hash, full_name, TRUE, now(), now()
FROM _auth_seed_accounts
ON CONFLICT (email) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM _auth_seed_accounts a
JOIN users u ON u.email = a.email
JOIN roles r ON r.code = a.role_code
ON CONFLICT (user_id, role_id) DO NOTHING;

COMMIT;
