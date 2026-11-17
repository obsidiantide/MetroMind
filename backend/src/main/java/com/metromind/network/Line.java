package com.metromind.network;

import java.util.List;

/**
 * A metro line. On an ordinary line trains run back and forth between the two terminals, which the simulation
 * models as a closed loop of "stops": 0..n-1 outbound, then n-2..1 inbound. On a ring line trains run round the
 * circle in station order without reversing, so loop stop {@code k} is simply station {@code k}; a ring served in
 * both directions is two ring lines over the same stations, one listing them in reverse.
 *
 * @param stationIds     ordered stations from terminal A to terminal B
 * @param runSeconds     nominal running time between consecutive stations (size = stations - 1); a ring also
 *                       gives the run from its last station back to the first (size = stations)
 * @param trainCount     trains in service on this line
 * @param trainCapacity  passengers per train
 * @param spareTrains    extra trains kept in a depot next to the first terminal; strategies that dispatch from the
 *                       depot may bring them into service (0 if the network file leaves it out)
 * @param path           track alignment as {@code [lon, lat]} pairs from terminal A to terminal B, for drawing and
 *                       spatial queries; empty when only the stations are known (the track is then the straight
 *                       segments between them); a ring's path runs from its first station round to the first again
 * @param ring           whether trains circulate round the stations instead of reversing at terminals
 */
public record Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                   int trainCount, int trainCapacity, int spareTrains, List<List<Double>> path, boolean ring) {

    /** Ordinary (non-ring) line with a track alignment. */
    public Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                int trainCount, int trainCapacity, int spareTrains, List<List<Double>> path) {
        this(id, name, color, stationIds, runSeconds, trainCount, trainCapacity, spareTrains, path, false);
    }

    /** Line whose track is the straight segments between its stations. */
    public Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                int trainCount, int trainCapacity, int spareTrains) {
        this(id, name, color, stationIds, runSeconds, trainCount, trainCapacity, spareTrains, List.of());
    }

    /** Line with no spare trains in its depot. */
    public Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                int trainCount, int trainCapacity) {
        this(id, name, color, stationIds, runSeconds, trainCount, trainCapacity, 0);
    }

    public Line {
        if (spareTrains < 0) {
            throw new IllegalArgumentException("Line " + id + ": spareTrains must not be negative");
        }
        if (stationIds.size() < 2) {
            throw new IllegalArgumentException("Line " + id + " needs at least two stations");
        }
        if (ring && stationIds.size() < 3) {
            throw new IllegalArgumentException("Ring line " + id + " needs at least three stations");
        }
        if (!ring && runSeconds.size() != stationIds.size() - 1) {
            throw new IllegalArgumentException("Line " + id + ": runSeconds must have stations-1 entries");
        }
        if (ring && runSeconds.size() != stationIds.size()) {
            throw new IllegalArgumentException("Ring line " + id
                    + ": runSeconds must have one entry per station (the last closes the ring)");
        }
        if (stationIds.stream().distinct().count() != stationIds.size()) {
            throw new IllegalArgumentException("Line " + id + " lists a station twice");
        }
        stationIds = List.copyOf(stationIds);
        runSeconds = List.copyOf(runSeconds);
        if (path == null) {
            path = List.of();
        }
        if (path.size() == 1) {
            throw new IllegalArgumentException("Line " + id + ": path needs at least two points");
        }
        for (List<Double> point : path) {
            if (point.size() != 2) {
                throw new IllegalArgumentException("Line " + id + ": path points must be [lon, lat]");
            }
        }
        path = path.stream().map(List::copyOf).toList();
    }

    /** Whether the network file gave the real track alignment rather than just the stations. */
    public boolean hasPath() {
        return !path.isEmpty();
    }

    /** Number of stations on the line. */
    public int stationCount() {
        return stationIds.size();
    }

    /** Number of stops in the full loop: outbound + inbound with terminals counted once, or the ring's stations. */
    public int loopLength() {
        int n = stationIds.size();
        return ring ? n : 2 * (n - 1);
    }

    /** Position in {@link #stationIds()} of the station served at loop stop {@code k}. */
    public int positionAtStop(int k) {
        int n = stationIds.size();
        return ring || k < n ? k : loopLength() - k;
    }

    /** Station served at loop stop {@code k}. */
    public String stationAtStop(int k) {
        return stationIds.get(positionAtStop(k));
    }

    /** Direction of travel when leaving loop stop {@code k}: +1 outbound (always on a ring), -1 inbound. */
    public int directionAtStop(int k) {
        return ring || k < stationIds.size() - 1 ? 1 : -1;
    }

    /** Whether loop stop {@code k} is a terminal, where trains reverse. A ring has none. */
    public boolean isTerminalStop(int k) {
        return !ring && (k == 0 || k == stationIds.size() - 1);
    }

    /**
     * Position reached by travelling one station from {@code position} in {@code direction}, or -1 if the line
     * ends there (or a ring is not run that way).
     */
    public int nextPosition(int position, int direction) {
        int n = stationIds.size();
        if (ring) {
            return direction > 0 ? (position + 1) % n : -1;
        }
        int next = position + direction;
        return next >= 0 && next < n ? next : -1;
    }

    /** Number of track sections: one between each pair of neighbouring stations (a ring has one more). */
    public int sectionCount() {
        return runSeconds.size();
    }

    /** Position of the station at the far end of section {@code i}, which starts at position {@code i}. */
    public int sectionEnd(int i) {
        return (i + 1) % stationIds.size();
    }

    /**
     * Section joining the stations at positions {@code a} and {@code b} (in either order), or -1 if they are not
     * neighbours on this line.
     */
    public int sectionBetween(int a, int b) {
        if (a < 0 || b < 0) {
            return -1;
        }
        if (Math.abs(a - b) == 1) {
            return Math.min(a, b);
        }
        int last = stationIds.size() - 1;
        return ring && Math.min(a, b) == 0 && Math.max(a, b) == last ? last : -1;
    }

    /** Section a train uses when leaving loop stop {@code k}. */
    public int sectionFromStop(int k) {
        int pos = positionAtStop(k);
        return directionAtStop(k) > 0 ? pos : pos - 1;
    }

    /**
     * Station a train leaving {@code position} in {@code direction} is signed towards: the terminal it heads for,
     * or on a ring (which has no terminal) the next station.
     */
    public String towardsStationId(int position, int direction) {
        if (ring) {
            return stationIds.get(nextPosition(position, 1));
        }
        return direction > 0 ? stationIds.get(stationIds.size() - 1) : stationIds.get(0);
    }

    /** Nominal run time of the segment that starts at loop stop {@code k}. */
    public int runSecondsFromStop(int k) {
        return runSeconds.get(sectionFromStop(k));
    }

    /** Nominal time for a train to complete the loop, excluding dwell. */
    public int loopRunSeconds() {
        int sum = runSeconds.stream().mapToInt(Integer::intValue).sum();
        return ring ? sum : 2 * sum;
    }
}
