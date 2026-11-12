CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE station (
    id                       VARCHAR(16) PRIMARY KEY,
    name                     VARCHAR(128) NOT NULL,
    location                 GEOGRAPHY(POINT, 4326) NOT NULL,
    base_arrivals_per_minute DOUBLE PRECISION NOT NULL
);
CREATE INDEX station_location_idx ON station USING GIST (location);

CREATE TABLE line (
    id          VARCHAR(16) PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    color       VARCHAR(16) NOT NULL,
    path        GEOMETRY(LINESTRING, 4326) NOT NULL,
    train_count INT NOT NULL
);

CREATE TABLE incident (
    id          BIGSERIAL PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sim_time    DOUBLE PRECISION NOT NULL,
    kind        VARCHAR(32) NOT NULL,
    train_id    VARCHAR(32) NOT NULL,
    seconds     DOUBLE PRECISION NOT NULL
);
