package com.metromind.sim;

import java.util.List;

public record SimulationSnapshot(double simTime, String clock, String strategy, List<TrainView> trains,
                                 List<StationView> stations, List<SimEvent> recentEvents, Metrics metrics) {

    public record TrainView(String id, String lineId, String fromStationId, String toStationId, double progress,
                            double lat, double lon, TrainPhase phase, double delaySeconds, double load,
                            int capacity, boolean failed) {
    }

    /**
     * @param heldAtEntrance passengers kept outside because the platforms are full (not included in {@code waiting})
     * @param leftBehind     passengers so far who could not board a full train in their direction (counted per train)
     * @param queues         waiting passengers broken down by line and direction of travel
     */
    public record StationView(String id, double waiting, double predictedWaiting15m, String overloadRisk,
                              double heldAtEntrance, double leftBehind, List<QueueView> queues) {
    }

    /** @param towardsStationId terminal the queued passengers are travelling towards */
    public record QueueView(String lineId, String towardsStationId, double waiting) {
    }

    /**
     * @param passengersServed    boardings so far, counting each line of a journey with transfers
     * @param passengersDelivered passengers who reached their destination
     * @param transfers           line changes made at interchanges
     * @param passengersLeftBehind passengers who could not board a full train, counted once per train missed
     * @param passengersHeldAtEntrance passengers currently kept outside stations with full platforms
     */
    public record Metrics(double averageDelaySeconds, double maxDelaySeconds, double totalWaiting,
                          double passengersServed, double passengersDelivered, double transfers,
                          double passengersLeftBehind, double passengersHeldAtEntrance,
                          double headwayCoefficientOfVariation) {
    }
}
