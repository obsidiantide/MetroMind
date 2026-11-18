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
 */
public record Branch(String junctionStationId, List<String> stationIds, List<Integer> runSeconds, double share) {

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
        stationIds = List.copyOf(stationIds);
        runSeconds = List.copyOf(runSeconds);
    }

    /** The branch's far terminal. */
    public String terminalStationId() {
        return stationIds.get(stationIds.size() - 1);
    }
}
