-- Bootstrap local PostgreSQL for Tickefy backend services.
-- Run with psql as a PostgreSQL superuser while connected to the maintenance DB:
--   psql -U postgres -d postgres -f tickefy-backend/data/00-create-service-databases.sql
--
-- The backend README and .env.example files expect a shared DB named tickefy
-- with one schema per service. Some application.yml defaults still point to
-- service-specific databases, so this script creates those databases as well.

\set ON_ERROR_STOP on

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tickefy') THEN
        CREATE ROLE tickefy LOGIN PASSWORD 'change_me';
    END IF;
END
$$;

SELECT 'CREATE DATABASE tickefy OWNER tickefy'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'tickefy')\gexec

SELECT format('CREATE DATABASE %I OWNER tickefy', db_name)
FROM (
    VALUES
        ('tickefy_auth'),
        ('tickefy_event'),
        ('tickefy_inventory'),
        ('tickefy_order'),
        ('tickefy_notification'),
        ('tickefy_eticket'),
        ('tickefy_checkin')
) AS databases(db_name)
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = db_name)\gexec

\connect tickefy

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE SCHEMA IF NOT EXISTS auth_service AUTHORIZATION tickefy;
CREATE SCHEMA IF NOT EXISTS event_service AUTHORIZATION tickefy;
CREATE SCHEMA IF NOT EXISTS inventory_service AUTHORIZATION tickefy;
CREATE SCHEMA IF NOT EXISTS order_service AUTHORIZATION tickefy;
CREATE SCHEMA IF NOT EXISTS payment_service AUTHORIZATION tickefy;
CREATE SCHEMA IF NOT EXISTS notification_service AUTHORIZATION tickefy;
CREATE SCHEMA IF NOT EXISTS eticket_service AUTHORIZATION tickefy;
CREATE SCHEMA IF NOT EXISTS checkin_service AUTHORIZATION tickefy;
CREATE SCHEMA IF NOT EXISTS csv_ingestion_service AUTHORIZATION tickefy;
CREATE SCHEMA IF NOT EXISTS ai_bio_schema AUTHORIZATION tickefy;
CREATE SCHEMA IF NOT EXISTS ai_bio_service AUTHORIZATION tickefy;

GRANT CONNECT ON DATABASE tickefy TO tickefy;
GRANT USAGE, CREATE ON SCHEMA
    auth_service,
    event_service,
    inventory_service,
    order_service,
    payment_service,
    notification_service,
    eticket_service,
    checkin_service,
    csv_ingestion_service,
    ai_bio_schema,
    ai_bio_service
TO tickefy;

ALTER DEFAULT PRIVILEGES FOR ROLE tickefy IN SCHEMA auth_service GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tickefy;
ALTER DEFAULT PRIVILEGES FOR ROLE tickefy IN SCHEMA event_service GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tickefy;
ALTER DEFAULT PRIVILEGES FOR ROLE tickefy IN SCHEMA inventory_service GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tickefy;
ALTER DEFAULT PRIVILEGES FOR ROLE tickefy IN SCHEMA order_service GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tickefy;
ALTER DEFAULT PRIVILEGES FOR ROLE tickefy IN SCHEMA payment_service GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tickefy;
ALTER DEFAULT PRIVILEGES FOR ROLE tickefy IN SCHEMA notification_service GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tickefy;
ALTER DEFAULT PRIVILEGES FOR ROLE tickefy IN SCHEMA eticket_service GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tickefy;
ALTER DEFAULT PRIVILEGES FOR ROLE tickefy IN SCHEMA checkin_service GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tickefy;
ALTER DEFAULT PRIVILEGES FOR ROLE tickefy IN SCHEMA csv_ingestion_service GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tickefy;
ALTER DEFAULT PRIVILEGES FOR ROLE tickefy IN SCHEMA ai_bio_schema GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tickefy;
ALTER DEFAULT PRIVILEGES FOR ROLE tickefy IN SCHEMA ai_bio_service GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tickefy;
