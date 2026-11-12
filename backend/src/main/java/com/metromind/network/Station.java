package com.metromind.network;

/**
 * A physical station. Coordinates are WGS84 (lat/lon) so they map directly to PostGIS geography points.
 *
 * @param baseArrivalsPerMinute average passengers entering the station per minute at the off-peak level
 */
public record Station(String id, String name, double lat, double lon, double baseArrivalsPerMinute) {
}
