package com.metromind.sim;

import java.util.LinkedHashMap;
import java.util.Map;

final class StationState {
    final String id;
    /** Passengers waiting per line. */
    final Map<String, Double> waiting = new LinkedHashMap<>();
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
        s.waitSeconds = waitSeconds;
        s.served = served;
        s.peakWaiting = peakWaiting;
        return s;
    }
}
