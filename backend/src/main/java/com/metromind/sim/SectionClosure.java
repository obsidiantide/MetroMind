package com.metromind.sim;

/**
 * A closed stretch of track between two adjacent stations of a line (signal or track failure). The closure covers
 * both directions: no train may enter the section until it reopens. Trains already inside it finish their run.
 */
final class SectionClosure {
    final String lineId;
    /** Position (index in the line's station list) of the section's end nearer the line's first station. */
    final int lowerPosition;
    ClosureResponse response;
    double remainingSeconds;
    /** Simulated time at which the section was closed. */
    final double startedAt;

    SectionClosure(String lineId, int lowerPosition, ClosureResponse response, double remainingSeconds,
                   double startedAt) {
        this.lineId = lineId;
        this.lowerPosition = lowerPosition;
        this.response = response;
        this.remainingSeconds = remainingSeconds;
        this.startedAt = startedAt;
    }

    SectionClosure copy() {
        return new SectionClosure(lineId, lowerPosition, response, remainingSeconds, startedAt);
    }
}
