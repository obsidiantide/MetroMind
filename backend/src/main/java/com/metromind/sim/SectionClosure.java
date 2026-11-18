package com.metromind.sim;

/**
 * A closed stretch of track between two adjacent stations of a line (signal or track failure). The closure covers
 * both directions and every service pattern that runs over it: no train may enter the section until it reopens.
 * Trains already inside it finish their run.
 */
final class SectionClosure {
    final String lineId;
    /** End of the section nearer the line's first terminal. */
    final String fromStationId;
    /** End of the section further from the line's first terminal. */
    final String toStationId;
    ClosureResponse response;
    double remainingSeconds;
    /** Simulated time at which the section was closed. */
    final double startedAt;

    SectionClosure(String lineId, String fromStationId, String toStationId, ClosureResponse response,
                   double remainingSeconds, double startedAt) {
        this.lineId = lineId;
        this.fromStationId = fromStationId;
        this.toStationId = toStationId;
        this.response = response;
        this.remainingSeconds = remainingSeconds;
        this.startedAt = startedAt;
    }

    /** Whether this closure is the track between {@code a} and {@code b} (in either order) on line {@code line}. */
    boolean covers(String line, String a, String b) {
        return lineId.equals(line) && ((fromStationId.equals(a) && toStationId.equals(b))
                || (fromStationId.equals(b) && toStationId.equals(a)));
    }

    SectionClosure copy() {
        return new SectionClosure(lineId, fromStationId, toStationId, response, remainingSeconds, startedAt);
    }
}
