package com.metromind.network;

import java.util.List;

/** Spatial lookups over the network's stations. Distances are in metres along the earth's surface. */
public interface StationLocator {

    /** A station and its distance from the queried point. */
    record StationDistance(Station station, double distanceMeters) {
    }

    /** The {@code limit} stations closest to the point, nearest first. */
    List<StationDistance> nearest(double lat, double lon, int limit);

    /** Every station within {@code radiusMeters} of the point, nearest first. */
    List<StationDistance> within(double lat, double lon, double radiusMeters);

    static void checkPoint(double lat, double lon) {
        if (!(lat >= -90 && lat <= 90) || !(lon >= -180 && lon <= 180)) {
            throw new IllegalArgumentException("Point (%s, %s) is not a valid WGS84 lat/lon".formatted(lat, lon));
        }
    }
}
