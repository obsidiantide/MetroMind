package com.metromind.network;

import java.util.List;

/**
 * A metro line. Trains run back and forth between the two terminals, which the simulation models
 * as a closed loop of "stops": 0..n-1 outbound, then n-2..1 inbound.
 *
 * @param stationIds     ordered stations from terminal A to terminal B
 * @param runSeconds     nominal running time between consecutive stations (size = stations - 1)
 * @param trainCount     trains in service on this line
 * @param trainCapacity  passengers per train
 */
public record Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                   int trainCount, int trainCapacity) {

    public Line {
        if (stationIds.size() < 2) {
            throw new IllegalArgumentException("Line " + id + " needs at least two stations");
        }
        if (runSeconds.size() != stationIds.size() - 1) {
            throw new IllegalArgumentException("Line " + id + ": runSeconds must have stations-1 entries");
        }
        stationIds = List.copyOf(stationIds);
        runSeconds = List.copyOf(runSeconds);
    }

    /** Number of stops in the full loop (outbound + inbound, terminals counted once). */
    public int loopLength() {
        return 2 * (stationIds.size() - 1);
    }

    /** Station served at loop stop {@code k}. */
    public String stationAtStop(int k) {
        int n = stationIds.size();
        int idx = k < n ? k : loopLength() - k;
        return stationIds.get(idx);
    }

    /** Direction of travel when leaving loop stop {@code k}: +1 outbound, -1 inbound. */
    public int directionAtStop(int k) {
        return k < stationIds.size() - 1 ? 1 : -1;
    }

    /** Nominal run time of the segment that starts at loop stop {@code k}. */
    public int runSecondsFromStop(int k) {
        int n = stationIds.size();
        if (k < n - 1) {
            return runSeconds.get(k);
        }
        int idx = loopLength() - k - 1;
        return runSeconds.get(idx);
    }

    /** Nominal time for a train to complete the loop, excluding dwell. */
    public int loopRunSeconds() {
        return 2 * runSeconds.stream().mapToInt(Integer::intValue).sum();
    }
}
