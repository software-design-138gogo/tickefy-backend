# Tickefy local database data

This folder contains local/dev SQL helpers that mirror the application seeders.

## Files

- `00-create-service-databases.sql`: psql bootstrap script for local PostgreSQL roles, databases, and service schemas.
- `auth-service/seed-dev-auth.sql`: fixed dev login accounts from `TestCustomerSeeder`.
- `event-service/seed-dev-event-catalog.sql`: artist catalog from `DatabaseSeeder` plus fixed event anchors from `EventAnchorSeeder`.
- `inventory-service/seed-dev-inventory.sql`: fixed ticket types and inventory rows from `DevSeedService`.

## Usage

Run the database bootstrap script as a PostgreSQL superuser:

```powershell
psql -U postgres -d postgres -f tickefy-backend/data/00-create-service-databases.sql
```

Run service migrations first, then run the relevant seed script against the database/schema used by that service.
The seed scripts default to the service schemas used by `.env.example`:

```powershell
psql -U tickefy -d tickefy -f tickefy-backend/data/auth-service/seed-dev-auth.sql
psql -U tickefy -d tickefy -f tickefy-backend/data/event-service/seed-dev-event-catalog.sql
psql -U tickefy -d tickefy -f tickefy-backend/data/inventory-service/seed-dev-inventory.sql
```

These scripts are idempotent. Re-running them inserts missing rows only and does not top up existing inventory counters.
