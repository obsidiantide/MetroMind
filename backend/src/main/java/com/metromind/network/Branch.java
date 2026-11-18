package com.metromind.network;

import java.util.List;

/**
 * A branch of a line: past {@code junctionStationId} some of the line's trains leave the main route and run to
 * the branch's own stations instead. Trains of both routes share the trunk from the line's first terminal to the
 * junction.
 *
 * @param junctionStationId station of the line's main route where the branch diverges
 * @param stationIds        branch stations in running order away from the junction; the last is its terminal
 * @param runSeconds        nominal run times: junction to first branch station, then between branch stations
 *                          (size = branch stations)
 * @param share             fraction of the line's trains that run to the branch, in (0, 1)
 * @param path              track alignment as {@code [lon, lat]} pairs from the junction to the branch terminal;
 *                          empty when only the stations are known
 * @param crossingSeconds   for a flat junction, how long a train leaving the junction for the branch takes to
 *                          cross the opposite main track, during which trains running the other way on the main
 *                          route must wait; 0 (the default) for a grade-separated junction with no conflict
 */
public record Branch(String junctionStationId, List<String> stationIds, List<Integer> runSeconds, double share,
                     List<List<Double>> path, double crossingSeconds) {

    /** Grade-separated branch with a known track alignment. */
    public Branch(String junctionStationId, List<String> stationIds, List<Integer> runSeconds, double share,
                  List<List<Double>> path) {
        this(junctionStationId, stationIds, runSeconds, share, path, 0);
    }

    /** Grade-separated branch whose track is the straight segments between its stations. */
    public Branch(String junctionStationId, List<String> stationIds, List<Integer> runSeconds, double share) {
        this(junctionStationId, stationIds, runSeconds, share, List.of());
    }

    public Branch {
        if (junctionStationId == null) {
            throw new IllegalArgumentException("Branch needs a junction station");
        }
        if (stationIds == null || stationIds.isEmpty()) {
            throw new IllegalArgumentException("Branch at " + junctionStationId + " needs at least one station");
        }
        if (runSeconds == null || runSeconds.size() != stationIds.size()) {
            throw new IllegalArgumentException("Branch at " + junctionStationId
                    + ": runSeconds must have one entry per branch station (the first leaves the junction)");
        }
        if (!(share > 0 && share < 1)) {
            throw new IllegalArgumentException("Branch at " + junctionStationId + ": share must be between 0 and 1");
        }
        if (!(crossingSeconds >= 0)) {
            throw new IllegalArgumentException("Branch at " + junctionStationId
                    + ": crossingSeconds must not be negative");
        }
        stationIds = List.copyOf(stationIds);
        runSeconds = List.copyOf(runSeconds);
        path = path == null ? List.of() : path.stream().map(List::copyOf).toList();
        if (path.size() == 1 || path.stream().anyMatch(point -> point.size() != 2)) {
            throw new IllegalArgumentException("Branch at " + junctionStationId
                    + ": path needs at least two [lon, lat] points");
        }
    }

    /** Whether trains leaving for the branch cross the opposite main track at the junction. */
    public boolean flatJunction() {
        return crossingSeconds > 0;
    }

    /** Whether the network file gave the branch's real track alignment. */
    public boolean hasPath() {
        return !path.isEmpty();
    }

    /** The junction followed by the branch stations, in the order the branch track runs. */
    public List<String> trackStationIds() {
        List<String> ids = new java.util.ArrayList<>();
        ids.add(junctionStationId);
        ids.addAll(stationIds);
        return List.copyOf(ids);
    }

    /** The branch's far terminal. */
    public String terminalStationId() {
        return stationIds.get(stationIds.size() - 1);
    }
}
