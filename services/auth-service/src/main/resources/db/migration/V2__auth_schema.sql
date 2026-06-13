-- V2__auth_schema.sql — auth domain (schema = auth_service)
-- KHONG FK xuyen service. FK noi bo auth_service duoc phep.

-- users -----------------------------------------------------------
CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- roles (lookup noi bo) -------------------------------------------
CREATE TABLE roles (
    id   SMALLINT     PRIMARY KEY,
    code VARCHAR(32)  NOT NULL,
    CONSTRAINT uq_roles_code UNIQUE (code)
);
INSERT INTO roles (id, code) VALUES
    (1, 'AUDIENCE'),
    (2, 'ORGANIZER'),
    (3, 'CHECKIN_STAFF'),
    (4, 'ADMIN');

-- user_roles (M:N) ------------------------------------------------
CREATE TABLE user_roles (
    user_id UUID     NOT NULL,
    role_id SMALLINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id)
);

-- refresh_tokens (luu HASH, khong plaintext) ----------------------
CREATE TABLE refresh_tokens (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL,
    token_hash VARCHAR(64)  NOT NULL,
    issued_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_user    ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_expires ON refresh_tokens (expires_at);

-- login_audit (OPTIONAL - disabled)
-- CREATE TABLE login_audit (
--     id         UUID        PRIMARY KEY,
--     user_id    UUID,
--     email      VARCHAR(320) NOT NULL,
--     success    BOOLEAN     NOT NULL,
--     ip_address VARCHAR(45),
--     created_at TIMESTAMPTZ NOT NULL DEFAULT now()
-- );
-- CREATE INDEX idx_login_audit_user ON login_audit (user_id);
