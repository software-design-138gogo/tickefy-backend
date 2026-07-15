-- V2: Create Event Service business tables
-- Author: Duong
-- Date: 13/06/2026

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Table: venues
CREATE TABLE venues (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    address TEXT,
    city VARCHAR(100),
    capacity INT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Table: artists
CREATE TABLE artists (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    bio TEXT,
    bio_generated_at TIMESTAMPTZ,
    press_kit_url VARCHAR(500),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Table: concerts (core entity)
CREATE TABLE concerts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    venue_id UUID REFERENCES venues(id),
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    sale_start_at TIMESTAMPTZ,
    sale_end_at TIMESTAMPTZ,
    event_date TIMESTAMPTZ NOT NULL,
    created_by UUID,  -- Reference to auth_schema.users(id), no FK across services
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Table: concert_artists (join table)
CREATE TABLE concert_artists (
    concert_id UUID REFERENCES concerts(id) ON DELETE CASCADE,
    artist_id UUID REFERENCES artists(id) ON DELETE CASCADE,
    PRIMARY KEY (concert_id, artist_id)
);

-- Table: concert_zones (ticket types / seat zones per concert)
CREATE TABLE concert_zones (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    concert_id UUID REFERENCES concerts(id) ON DELETE CASCADE,
    ticket_type_name VARCHAR(50) NOT NULL,  -- SVIP, VIP, CAT1, CAT2, GA (synced with inventory-service)
    zone_name VARCHAR(100) NOT NULL,
    svg_element_id VARCHAR(100),            -- SVG element id for frontend click mapping
    seat_map_url VARCHAR(500),              -- URL to SVG file stored in MinIO/Object Storage
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes for common query patterns
CREATE INDEX idx_concerts_status ON concerts(status);
CREATE INDEX idx_concerts_event_date ON concerts(event_date);
CREATE INDEX idx_concerts_venue_id ON concerts(venue_id);
CREATE INDEX idx_concert_zones_concert_id ON concert_zones(concert_id);
