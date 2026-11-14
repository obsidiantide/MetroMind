package com.metromind.sim;

/**
 * One row of a station departure board.
 *
 * @param towardsStationId terminal the train is heading for after this departure
 * @param plannedTime      timetabled departure, in simulation seconds
 * @param plannedClock     {@code plannedTime} as HH:MM:SS
 * @param expectedTime     live estimate from the train's current position, nominal run times and any blockage
 * @param delaySeconds     {@code expectedTime - plannedTime}; negative when the train is running early
 */
public record Departure(String trainId, String lineId, String towardsStationId, double plannedTime,
                        String plannedClock, double expectedTime, double delaySeconds) {
}
