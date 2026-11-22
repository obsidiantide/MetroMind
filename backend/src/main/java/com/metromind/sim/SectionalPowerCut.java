package com.metromind.sim;

import java.util.List;
import java.util.Set;

/**
 * A sectional traction power failure: one feeder section of a line, from station {@code fromStationId} to
 * {@code toStationId} along the line, is without current. The track between them (both directions) and the
 * platforms of the stations strictly inside the stretch are dead; the two end stations are fed from the
 * neighbouring sections, so trains can still call there and turn back or wait.
 */
final class SectionalPowerCut {
    final String lineId;
    final String fromStationId;
    final String toStationId;
    /** Stations of the stretch in line order, ends included. */
    final List<String> stationIds;
    private final Set<String> deadSegments;
    double remainingSeconds;
    /** Simulated time at which the power was lost. */
    final double startedAt;

    SectionalPowerCut(String lineId, List<String> stationIds, double remainingSeconds, double startedAt) {
        if (stationIds.size() < 2) {
            throw new IllegalArgumentException("A dead feeder section spans at least two stations");
        }
        this.lineId = lineId;
        this.stationIds = List.copyOf(stationIds);
        this.fromStationId = stationIds.get(0);
        this.toStationId = stationIds.get(stationIds.size() - 1);
        java.util.Set<String> segments = new java.util.HashSet<>();
        for (int i = 0; i + 1 < stationIds.size(); i++) {
            segments.add(key(stationIds.get(i), stationIds.get(i + 1)));
        }
        this.deadSegments = Set.copyOf(segments);
        this.remainingSeconds = remainingSeconds;
        this.startedAt = startedAt;
    }

    private static String key(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /** Whether the track between neighbouring stations {@code a} and {@code b} of line {@code line} is dead. */
    boolean deadBetween(String line, String a, String b) {
        return lineId.equals(line) && deadSegments.contains(key(a, b));
    }

    /** Whether a train of line {@code line} standing at {@code stationId} is on a dead platform. */
    boolean deadAt(String line, String stationId) {
        return lineId.equals(line) && stationIds.indexOf(stationId) > 0
                && stationIds.indexOf(stationId) < stationIds.size() - 1;
    }

    /** Whether this cut is the same stretch of the same line, whichever end it was given from. */
    boolean sameStretch(String line, List<String> stations) {
        return lineId.equals(line) && Set.copyOf(stationIds).equals(Set.copyOf(stations));
    }

    SectionalPowerCut copy() {
        return new SectionalPowerCut(lineId, stationIds, remainingSeconds, startedAt);
    }
}
