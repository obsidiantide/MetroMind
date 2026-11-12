package com.metromind.sim;

import java.util.List;

public record SimulationSnapshot(double simTime, String clock, String strategy, List<TrainView> trains,
                                 List<StationView> stations, List<SimEvent> recentEvents, Metrics metrics) {

    public record TrainView(String id, String lineId, String fromStationId, String toStationId, double progress,
                            double lat, double lon, TrainPhase phase, double delaySeconds, double load,
                            int capacity, boolean failed) {
    }

    public record StationView(String id, double waiting, double predictedWaiting15m, String overloadRisk) {
    }

    public record Metrics(double averageDelaySeconds, double maxDelaySeconds, double totalWaiting,
                          double passengersServed, double headwayCoefficientOfVariation) {
    }
}
