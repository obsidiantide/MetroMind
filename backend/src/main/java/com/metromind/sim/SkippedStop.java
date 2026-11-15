package com.metromind.sim;

/**
 * A late train ran through a station without stopping, under a strategy that skips quiet stops.
 *
 * @param stationId              station the train ran through
 * @param simTime                simulated time it passed the platform
 * @param latenessSeconds        how far behind its timetable the train was
 * @param passengersCarriedPast  passengers on board who would have got off there and ride on to the next stop
 */
public record SkippedStop(String trainId, String lineId, String stationId, double simTime, double latenessSeconds,
                          double passengersCarriedPast) {
}
