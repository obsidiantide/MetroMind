package com.metromind.sim;

import java.util.List;

public record SimulationSnapshot(double simTime, String clock, String strategy, List<TrainView> trains,
                                 List<StationView> stations, List<SimEvent> recentEvents, Metrics metrics,
                                 List<ClosedSectionView> closedSections, List<ActiveIncidentView> activeIncidents) {

    /**
     * @param patternId service pattern the train follows: the line id, or {@code line/branch-terminal} on a branch
     */
    public record TrainView(String id, String lineId, String patternId, String fromStationId, String toStationId,
                            double progress, double lat, double lon, TrainPhase phase, double delaySeconds,
                            double load, int capacity, boolean failed) {
    }

    /**
     * @param heldAtEntrance passengers kept outside because the platforms are full (not included in {@code waiting})
     * @param leftBehind     passengers so far who could not board a full train in their direction (counted per train)
     * @param queues         waiting passengers broken down by line and direction of travel
     * @param closed         trains run through without stopping; its passengers use the neighbouring stations
     */
    public record StationView(String id, double waiting, double predictedWaiting15m, String overloadRisk,
                              double heldAtEntrance, double leftBehind, List<QueueView> queues, boolean closed) {
    }

    /** @param towardsStationId terminal the queued passengers are travelling towards */
    public record QueueView(String lineId, String towardsStationId, double waiting) {
    }

    /**
     * A closed stretch of track. {@code fromStationId} is the end nearer the line's first station.
     *
     * @param response         what trains do on reaching the closure
     * @param remainingSeconds time until the section reopens
     */
    public record ClosedSectionView(String lineId, String fromStationId, String toStationId,
                                    ClosureResponse response, double remainingSeconds) {
    }

    public enum IncidentKind { TRAIN_DELAY, BREAKDOWN, SECTION_CLOSURE, STATION_CLOSURE }

    /**
     * An incident still in force, with its automatic clearing time.
     *
     * @param subjectId        train id, line id (track section) or station id
     * @param startedAt        simulated time the incident began
     * @param clearsAt         simulated time it is expected to clear on its own
     * @param remainingSeconds {@code clearsAt - simTime}
     */
    public record ActiveIncidentView(IncidentKind kind, String subjectId, String description, double startedAt,
                                     double clearsAt, double remainingSeconds) {
    }

    /**
     * @param passengersServed    boardings so far, counting each line of a journey with transfers
     * @param passengersDelivered passengers who reached their destination
     * @param transfers           line changes made at interchanges
     * @param passengersLeftBehind passengers who could not board a full train, counted once per train missed
     * @param passengersHeldAtEntrance passengers currently kept outside stations with full platforms
     * @param trainsInService    trains running on all lines, including any brought out of a depot
     * @param trainsInDepot      spare trains waiting in the depots
     * @param stopsSkipped       stops trains ran through under a skip-stop strategy
     * @param passengersCarriedPast passengers whose train ran through their destination, who rode back from the
     *                           next stop (not counted as transfers)
     */
    public record Metrics(double averageDelaySeconds, double maxDelaySeconds, double totalWaiting,
                          double passengersServed, double passengersDelivered, double transfers,
                          double passengersLeftBehind, double passengersHeldAtEntrance,
                          double headwayCoefficientOfVariation, int trainsInService, int trainsInDepot,
                          int stopsSkipped, double passengersCarriedPast) {
    }
}
