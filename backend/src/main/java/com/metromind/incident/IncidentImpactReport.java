package com.metromind.incident;

import java.util.List;

/**
 * Result of a what-if analysis for any kind of incident (train delay or breakdown, track-section or station
 * closure), compared across headway strategies. Each outcome has the same meaning as in {@link DelayImpactReport};
 * {@code bestStrategy} is the one with the smallest total passenger travel time in the incident run.
 *
 * @param incident  the incident injected at the start of the horizon
 */
public record IncidentImpactReport(BenchmarkReport.Scenario incident, double horizonSeconds,
                                   List<DelayImpactReport.StrategyOutcome> outcomes, String bestStrategy) {
}
