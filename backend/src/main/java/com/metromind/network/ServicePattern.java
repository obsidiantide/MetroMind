package com.metromind.network;

import java.util.List;

/**
 * One station sequence the trains of a line follow. An unbranched line has a single pattern, its own stations; a
 * branched line has one pattern per way its trains can run, all sharing the trunk from the line's first terminal
 * to the junction (see {@link Line#patterns()}).
 *
 * @param id               unique pattern id: the line id for the main pattern, {@code line/branch-terminal} for
 *                         a branch
 * @param lineId           line whose trains follow the pattern
 * @param stationIds       stations from the line's first terminal to the pattern's own far terminal
 * @param runSeconds       nominal run times between consecutive stations, as on {@link Line#runSeconds()}
 * @param ring             whether the pattern is a ring (only an unbranched ring line has one)
 * @param timingStationIds the line's timing points that lie on this pattern; empty for the default
 * @param trainCount       trains of the line that follow this pattern
 */
public record ServicePattern(String id, String lineId, List<String> stationIds, List<Integer> runSeconds,
                             boolean ring, List<String> timingStationIds, int trainCount) implements LoopTopology {

    public ServicePattern {
        if (stationIds.size() < 2) {
            throw new IllegalArgumentException("Service pattern " + id + " needs at least two stations");
        }
        if (runSeconds.size() != stationIds.size() - (ring ? 0 : 1)) {
            throw new IllegalArgumentException("Service pattern " + id + ": one run time per section expected");
        }
        if (trainCount < 0) {
            throw new IllegalArgumentException("Service pattern " + id + ": trainCount must not be negative");
        }
        stationIds = List.copyOf(stationIds);
        runSeconds = List.copyOf(runSeconds);
        timingStationIds = timingStationIds == null ? List.of() : List.copyOf(timingStationIds);
    }
}
