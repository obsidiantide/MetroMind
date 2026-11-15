package com.metromind.network;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Station lookups without a database, by great-circle (haversine) distance on a sphere of the earth's mean radius.
 * Over a city this is within about 0.5 % of PostGIS's spheroidal distance.
 */
@Component
@ConditionalOnProperty(name = "metromind.persistence.enabled", havingValue = "false")
public class InMemoryStationLocator implements StationLocator {

    static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private final MetroNetwork network;

    public InMemoryStationLocator(MetroNetwork network) {
        this.network = network;
    }

    @Override
    public List<StationDistance> nearest(double lat, double lon, int limit) {
        StationLocator.checkPoint(lat, lon);
        return byDistance(lat, lon).limit(limit).toList();
    }

    @Override
    public List<StationDistance> within(double lat, double lon, double radiusMeters) {
        StationLocator.checkPoint(lat, lon);
        return byDistance(lat, lon).takeWhile(d -> d.distanceMeters() <= radiusMeters).toList();
    }

    private Stream<StationDistance> byDistance(double lat, double lon) {
        // Ties are broken by id so results do not depend on the order stations are listed in.
        return network.stations().stream()
                .map(s -> new StationDistance(s, haversineMeters(lat, lon, s.lat(), s.lon())))
                .sorted(Comparator.comparingDouble(StationDistance::distanceMeters)
                        .thenComparing(d -> d.station().id()));
    }

    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
