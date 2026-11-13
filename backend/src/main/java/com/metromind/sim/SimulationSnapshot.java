package com.metromind.sim;

import java.util.List;

public record SimulationSnapshot(double simTime, String clock, String strategy, List<TrainView> trains,
                                 List<StationView> stations, List<SimEvent> recentEvents, Metrics metrics) {

    public record TrainView(String id, String lineId, String fromStationId, String toStationId, double progress,
                            double lat, double lon, TrainPhase phase, double delaySeconds, double load,
                            int capacity, boolean failed) {
    }

    /** @param queues waiting passengers broken down by line and direction of travel */
    public record StationView(String id, double waiting, double predictedWaiting15m, String overloadRisk,
                              List<QueueView> queues) {
    }

    /** @param towardsStationId terminal the queued passengers are travelling towards */
    public record QueueView(String lineId, String towardsStationId, double waiting) {
    }

    public record Metrics(double averageDelaySeconds, double maxDelaySeconds, double totalWaiting,
                          double passengersServed, double headwayCoefficientOfVariation) {
    }
}
