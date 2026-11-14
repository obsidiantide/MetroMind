package com.metromind.network;

/**
 * A physical station. Coordinates are WGS84 (lat/lon) so they map directly to PostGIS geography points.
 *
 * @param baseArrivalsPerMinute average passengers entering the station per minute at the off-peak level
 * @param platformCapacity      most passengers the platforms can hold at once, all queues together; {@code null}
 *                              means unlimited. When the platforms are full, new passengers are held at the
 *                              entrance (people changing lines are always let through).
 */
public record Station(String id, String name, double lat, double lon, double baseArrivalsPerMinute,
                      Integer platformCapacity) {

    public Station {
        if (platformCapacity != null && platformCapacity <= 0) {
            throw new IllegalArgumentException("Station " + id + ": platformCapacity must be positive");
        }
    }

    /** Station with unlimited platform space. */
    public Station(String id, String name, double lat, double lon, double baseArrivalsPerMinute) {
        this(id, name, lat, lon, baseArrivalsPerMinute, null);
    }

    public boolean hasPlatformLimit() {
        return platformCapacity != null;
    }
}
