package com.metromind.network;

import java.util.List;

/**
 * Lines whose platforms face each other across an island, so changing between them is a step across the platform
 * rather than a walk through the station: the two directions of a ring, or two lines sharing an island platform.
 *
 * @param lineIds    the lines (at least two) passengers can change between without walking
 * @param stationIds the stations where they can do so; empty for every station the lines have in common
 */
public record CrossPlatformInterchange(List<String> lineIds, List<String> stationIds) {

    public CrossPlatformInterchange {
        if (lineIds == null || lineIds.stream().distinct().count() < 2) {
            throw new IllegalArgumentException("A cross-platform interchange needs at least two different lines");
        }
        lineIds = List.copyOf(lineIds);
        stationIds = stationIds == null ? List.of() : List.copyOf(stationIds);
    }

    /** Whether changing from {@code fromLine} to {@code toLine} at {@code stationId} is cross-platform. */
    public boolean covers(String fromLine, String toLine, String stationId) {
        return !fromLine.equals(toLine) && lineIds.contains(fromLine) && lineIds.contains(toLine)
                && (stationIds.isEmpty() || stationIds.contains(stationId));
    }
}
