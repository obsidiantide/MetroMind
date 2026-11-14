package com.metromind.incident;

import java.util.List;

/**
 * Result of benchmarking headway strategies against the same set of random incident scenarios, all started from
 * one snapshot of the network.
 *
 * <p>For each scenario and strategy the benchmark measures the mean platform wait per boarding over the horizon:
 * passenger-seconds spent waiting at stations divided by the passengers who boarded a train. {@code meanWaitSeconds}
 * averages that over the scenarios and {@code p95WaitSeconds} is its 95th percentile (nearest rank), the
 * "bad day" a strategy has to live with. Rankings are by mean, then p95, then name, so ties are deterministic.
 *
 * @param seed       seed the scenarios were drawn with; the same seed and starting state give the same report
 * @param scenarios  the incidents every strategy was run against
 * @param rankings   one entry per strategy, best first
 */
public record BenchmarkReport(long seed, double horizonSeconds, List<Scenario> scenarios,
                              List<StrategyRanking> rankings) {

    public enum IncidentKind { DELAY, BREAKDOWN }

    /** One incident injected at the start of the horizon. */
    public record Scenario(IncidentKind kind, String trainId, double seconds) {
    }

    /**
     * @param waitSecondsPerScenario  mean wait per boarding in each scenario, in scenario order
     * @param meanTravelMinutes       mean over scenarios of total passenger time (platform wait + on board)
     */
    public record StrategyRanking(int rank, String strategy, double meanWaitSeconds, double p95WaitSeconds,
                                  double meanTravelMinutes, List<Double> waitSecondsPerScenario) {
    }
}
