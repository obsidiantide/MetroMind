package com.metromind.sim;

import java.util.LinkedHashMap;
import java.util.Map;

final class StationState {
    final String id;
    /** Passengers waiting per platform queue (line + direction of travel). */
    final Map<QueueKey, Double> waiting = new LinkedHashMap<>();
    /** Share of the station's passenger arrivals that joins each queue; values sum to 1. */
    final Map<QueueKey, Double> arrivalShare = new LinkedHashMap<>();
    /** Integral of waiting passengers over time (passenger-seconds). */
    double waitSeconds;
    double served;
    double peakWaiting;

    StationState(String id) {
        this.id = id;
    }

    double totalWaiting() {
        return waiting.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    StationState copy() {
        StationState s = new StationState(id);
        s.waiting.putAll(waiting);
        s.arrivalShare.putAll(arrivalShare);
        s.waitSeconds = waitSeconds;
        s.served = served;
        s.peakWaiting = peakWaiting;
        return s;
    }

    /**
     * Identifies one platform queue: passengers for {@code lineId} travelling in {@code direction}
     * (+1 towards the line's last station, -1 towards its first station).
     */
    record QueueKey(String lineId, int direction) {
    }
}
