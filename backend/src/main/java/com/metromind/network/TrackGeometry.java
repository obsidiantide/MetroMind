package com.metromind.network;

import java.util.ArrayList;
import java.util.List;

/**
 * The physical alignment of a line as a WGS84 polyline, with distances measured along it ("chainage").
 * Used to place stations on the track and to derive running times from the real track length rather than the
 * straight-line distance between stations.
 *
 * <p>Segment lengths are great-circle distances; projecting a point onto a segment uses a local equirectangular
 * approximation around that segment, which is accurate to well under a metre for metro-scale segments.
 */
public final class TrackGeometry {

    /** A point's position relative to the track: how far along it, and how far off it. */
    public record Projection(double chainageMeters, double offsetMeters) {
    }

    private final List<double[]> lonLat;
    private final double[] cumulative;

    /** @param lonLat track vertices as {@code [lon, lat]} pairs, in GeoJSON order */
    public TrackGeometry(List<double[]> lonLat) {
        if (lonLat.size() < 2) {
            throw new IllegalArgumentException("Track needs at least two points");
        }
        List<double[]> copy = new ArrayList<>(lonLat.size());
        for (double[] p : lonLat) {
            if (p.length < 2 || !(Math.abs(p[1]) <= 90) || !(Math.abs(p[0]) <= 180)) {
                throw new IllegalArgumentException("Track point must be [lon, lat] within WGS84");
            }
            copy.add(new double[]{p[0], p[1]});
        }
        this.lonLat = List.copyOf(copy);
        this.cumulative = new double[copy.size()];
        for (int i = 1; i < copy.size(); i++) {
            double[] a = copy.get(i - 1);
            double[] b = copy.get(i);
            cumulative[i] = cumulative[i - 1] + InMemoryStationLocator.haversineMeters(a[1], a[0], b[1], b[0]);
        }
    }

    public double lengthMeters() {
        return cumulative[cumulative.length - 1];
    }

    /** Track vertices as {@code [lon, lat]} pairs. */
    public List<double[]> points() {
        return lonLat.stream().map(p -> new double[]{p[0], p[1]}).toList();
    }

    /** Nearest point of the track to ({@code lat}, {@code lon}); ties go to the earliest chainage. */
    public Projection project(double lat, double lon) {
        Projection best = null;
        for (int i = 1; i < lonLat.size(); i++) {
            double[] a = lonLat.get(i - 1);
            double[] b = lonLat.get(i);
            double lat0 = Math.toRadians((a[1] + b[1]) / 2);
            // Local plane in metres-per-degree units, origin at a.
            double kx = Math.cos(lat0);
            double bx = (b[0] - a[0]) * kx;
            double by = b[1] - a[1];
            double px = (lon - a[0]) * kx;
            double py = lat - a[1];
            double len2 = bx * bx + by * by;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / len2));
            double footLon = a[0] + t * (b[0] - a[0]);
            double footLat = a[1] + t * (b[1] - a[1]);
            double offset = InMemoryStationLocator.haversineMeters(lat, lon, footLat, footLon);
            double chainage = cumulative[i - 1] + t * (cumulative[i] - cumulative[i - 1]);
            if (best == null || offset < best.offsetMeters() - 1e-9) {
                best = new Projection(chainage, offset);
            }
        }
        return best;
    }

    /**
     * The track point {@code chainageMeters} along it, as {@code [lon, lat]}; chainages outside the track are
     * clamped to its ends.
     */
    public double[] pointAt(double chainageMeters) {
        double c = Math.max(0, Math.min(lengthMeters(), chainageMeters));
        for (int i = 1; i < cumulative.length; i++) {
            if (c <= cumulative[i] || i == cumulative.length - 1) {
                double span = cumulative[i] - cumulative[i - 1];
                double t = span == 0 ? 0 : (c - cumulative[i - 1]) / span;
                double[] a = lonLat.get(i - 1);
                double[] b = lonLat.get(i);
                return new double[]{a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])};
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
