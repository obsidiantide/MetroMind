-- Incidents are no longer only about trains: a closure applies to a line (track section) or a station.
ALTER TABLE incident RENAME COLUMN train_id TO subject_id;
ALTER TABLE incident ADD COLUMN detail VARCHAR(256) NOT NULL DEFAULT '';
