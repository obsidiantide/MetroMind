package com.metromind.incident;

/**
 * A delay-impact analysis that was run against the live simulation, kept with its full result.
 *
 * @param id      increasing identifier, in the order analyses were recorded
 * @param simTime simulation time of the state the analysis started from
 */
public record AnalysisRecord(long id, double simTime, DelayImpactReport report) {
}
