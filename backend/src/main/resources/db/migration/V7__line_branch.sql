-- Branches of a line: past the junction some of its trains run to the branch terminal instead.
CREATE TABLE line_branch (
    line_id             VARCHAR(16) NOT NULL REFERENCES line (id) ON DELETE CASCADE,
    terminal_station_id VARCHAR(16) NOT NULL REFERENCES station (id),
    junction_station_id VARCHAR(16) NOT NULL REFERENCES station (id),
    share               DOUBLE PRECISION NOT NULL,
    path                GEOMETRY(LINESTRING, 4326) NOT NULL,
    PRIMARY KEY (line_id, terminal_station_id)
);
