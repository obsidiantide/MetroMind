package com.metromind.network;

import java.util.List;

/**
 * A metro line. On an ordinary line trains run back and forth between the two terminals; on a ring line they run
 * round the circle in station order without reversing (see {@link LoopTopology} for how both become a loop of
 * stops). A ring served in both directions is two ring lines over the same stations, one listing them in reverse.
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
 * @param timingStationIds stations where trains keep to the timetable, waiting there when early; empty for the
 *                       default: the terminals, or a ring's first station (a ring has no terminal to lay over at)
 */
public record Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                   int trainCount, int trainCapacity, int spareTrains, List<List<Double>> path, boolean ring,
                   List<String> timingStationIds) implements LoopTopology {

    /** Line with the default timing points. */
    public Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                int trainCount, int trainCapacity, int spareTrains, List<List<Double>> path, boolean ring) {
        this(id, name, color, stationIds, runSeconds, trainCount, trainCapacity, spareTrains, path, ring, List.of());
    }

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
        timingStationIds = timingStationIds == null ? List.of() : List.copyOf(timingStationIds);
        for (String sid : timingStationIds) {
            if (!stationIds.contains(sid)) {
                throw new IllegalArgumentException("Line " + id + ": timing point " + sid + " is not on the line");
            }
        }
    }

    /** Whether the network file gave the real track alignment rather than just the stations. */
    public boolean hasPath() {
        return !path.isEmpty();
    }

    /** The station sequences this line's trains follow: just the line's own stations while it has no branches. */
    public List<ServicePattern> patterns() {
        return List.of(new ServicePattern(id, id, stationIds, runSeconds, ring, timingStationIds, trainCount));
    }
}
