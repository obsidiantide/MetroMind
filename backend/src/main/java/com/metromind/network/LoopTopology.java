package com.metromind.network;

import java.util.List;

/**
 * The loop of stops trains run round on a station sequence. On an ordinary sequence trains run back and forth
 * between the two terminals, which the simulation models as a closed loop of "stops": 0..n-1 outbound, then
 * n-2..1 inbound. On a ring trains run round the circle in station order without reversing, so loop stop
 * {@code k} is simply station {@code k}.
 *
 * <p>Shared by a {@link Line} (its main station sequence) and each {@link ServicePattern} its trains follow.
 */
public interface LoopTopology {

    /** Ordered stations from terminal A to terminal B (or round a ring). */
    List<String> stationIds();

    /**
     * Nominal running time between consecutive stations (size = stations - 1); a ring also gives the run from its
     * last station back to the first (size = stations).
     */
    List<Integer> runSeconds();

    /** Whether trains circulate round the stations instead of reversing at terminals. */
    boolean ring();

    /**
     * Stations where trains keep to the timetable, waiting there when early; empty for the default: the terminals,
     * or a ring's first station (a ring has no terminal to lay over at).
     */
    List<String> timingStationIds();

    /** Number of stations in the sequence. */
    default int stationCount() {
        return stationIds().size();
    }

    /** Number of stops in the full loop: outbound + inbound with terminals counted once, or the ring's stations. */
    default int loopLength() {
        int n = stationIds().size();
        return ring() ? n : 2 * (n - 1);
    }

    /** Position in {@link #stationIds()} of the station served at loop stop {@code k}. */
    default int positionAtStop(int k) {
        int n = stationIds().size();
        return ring() || k < n ? k : loopLength() - k;
    }

    /** Station served at loop stop {@code k}. */
    default String stationAtStop(int k) {
        return stationIds().get(positionAtStop(k));
    }

    /** Direction of travel when leaving loop stop {@code k}: +1 outbound (always on a ring), -1 inbound. */
    default int directionAtStop(int k) {
        return ring() || k < stationIds().size() - 1 ? 1 : -1;
    }

    /** Whether loop stop {@code k} is a terminal, where trains reverse. A ring has none. */
    default boolean isTerminalStop(int k) {
        return !ring() && (k == 0 || k == stationIds().size() - 1);
    }

    /**
     * Whether trains keep to the timetable at loop stop {@code k}: one of the {@link #timingStationIds()}, or by
     * default a terminal, or the first station of a ring.
     */
    default boolean isTimingStop(int k) {
        if (!timingStationIds().isEmpty()) {
            return timingStationIds().contains(stationAtStop(k));
        }
        return isTerminalStop(k) || (ring() && k == 0);
    }

    /**
     * Position reached by travelling one station from {@code position} in {@code direction}, or -1 if the
     * sequence ends there (or a ring is not run that way).
     */
    default int nextPosition(int position, int direction) {
        int n = stationIds().size();
        if (ring()) {
            return direction > 0 ? (position + 1) % n : -1;
        }
        int next = position + direction;
        return next >= 0 && next < n ? next : -1;
    }

    /** Number of track sections: one between each pair of neighbouring stations (a ring has one more). */
    default int sectionCount() {
        return runSeconds().size();
    }

    /** Position of the station at the far end of section {@code i}, which starts at position {@code i}. */
    default int sectionEnd(int i) {
        return (i + 1) % stationIds().size();
    }

    /**
     * Section joining the stations at positions {@code a} and {@code b} (in either order), or -1 if they are not
     * neighbours in this sequence.
     */
    default int sectionBetween(int a, int b) {
        if (a < 0 || b < 0) {
            return -1;
        }
        if (Math.abs(a - b) == 1) {
            return Math.min(a, b);
        }
        int last = stationIds().size() - 1;
        return ring() && Math.min(a, b) == 0 && Math.max(a, b) == last ? last : -1;
    }

    /** Section a train uses when leaving loop stop {@code k}. */
    default int sectionFromStop(int k) {
        int pos = positionAtStop(k);
        return directionAtStop(k) > 0 ? pos : pos - 1;
    }

    /**
     * Station a train leaving {@code position} in {@code direction} is signed towards: the terminal it heads for,
     * or on a ring (which has no terminal) the next station.
     */
    default String towardsStationId(int position, int direction) {
        if (ring()) {
            return stationIds().get(nextPosition(position, 1));
        }
        return direction > 0 ? stationIds().get(stationIds().size() - 1) : stationIds().get(0);
    }

    /** Nominal run time of the segment that starts at loop stop {@code k}. */
    default int runSecondsFromStop(int k) {
        return runSeconds().get(sectionFromStop(k));
    }

    /** Nominal time for a train to complete the loop, excluding dwell. */
    default int loopRunSeconds() {
        int sum = runSeconds().stream().mapToInt(Integer::intValue).sum();
        return ring() ? sum : 2 * sum;
    }
}
