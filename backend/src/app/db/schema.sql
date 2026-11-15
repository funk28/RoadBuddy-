CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS hazards (
    id TEXT PRIMARY KEY,
    hazard_type TEXT NOT NULL,
    severity TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('live', 'expired', 'removed')),
    geog geography(Point, 4326) NOT NULL,
    sighting_count INTEGER NOT NULL CHECK (sighting_count > 0),
    last_confirmed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS hazards_geog_idx ON hazards USING GIST (geog);

CREATE INDEX IF NOT EXISTS hazards_live_type_idx ON hazards (hazard_type) WHERE status = 'live';

CREATE TABLE IF NOT EXISTS detections (
    id TEXT PRIMARY KEY,
    client_detection_id TEXT NOT NULL UNIQUE,
    hazard_id TEXT REFERENCES hazards (id),
    outcome TEXT NOT NULL CHECK (outcome IN ('created', 'matched')),
    hazard_type TEXT,
    severity TEXT,
    geog geography(Point, 4326) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    undo_expires_at TIMESTAMPTZ
);
