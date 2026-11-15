package com.metromind.sim;

/**
 * A train reversed early at an intermediate platform by a strategy that short-turns late trains.
 *
 * @param stationId        station where it turned back
 * @param simTime          simulated time of the reversal
 * @param latenessSeconds  how far behind its timetable the train was when it turned
 */
public record ShortTurn(String trainId, String lineId, String stationId, double simTime, double latenessSeconds) {
}
