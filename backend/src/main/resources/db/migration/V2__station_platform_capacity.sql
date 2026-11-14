-- Most passengers a station's platforms can hold at once; NULL means unlimited.
ALTER TABLE station ADD COLUMN platform_capacity INT CHECK (platform_capacity > 0);
