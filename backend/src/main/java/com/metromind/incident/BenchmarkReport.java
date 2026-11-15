package com.metromind.incident;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.metromind.sim.ClosureResponse;

import java.util.List;
import java.util.Map;

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

    public enum IncidentKind { DELAY, BREAKDOWN, SECTION_CLOSURE, STATION_CLOSURE }

    /**
     * One incident injected at the start of the horizon. Only the fields its kind needs are set (the others are
     * null and left out of the JSON): {@code trainId} for a delay or breakdown; {@code lineId},
     * {@code fromStationId}, {@code toStationId} and {@code response} for a track-section closure; {@code stationId}
     * for a station closure. {@code seconds} is how long it lasts.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Scenario(IncidentKind kind, String trainId, String lineId, String fromStationId,
                           String toStationId, ClosureResponse response, String stationId, double seconds) {

        public static Scenario train(IncidentKind kind, String trainId, double seconds) {
            return new Scenario(kind, trainId, null, null, null, null, null, seconds);
        }

        public static Scenario sectionClosure(String lineId, String fromStationId, String toStationId,
                                              ClosureResponse response, double seconds) {
            return new Scenario(IncidentKind.SECTION_CLOSURE, null, lineId, fromStationId, toStationId, response,
                    null, seconds);
        }

        public static Scenario stationClosure(String stationId, double seconds) {
            return new Scenario(IncidentKind.STATION_CLOSURE, null, null, null, null, null, stationId, seconds);
        }
    }

    /**
     * @param waitSecondsPerScenario  mean wait per boarding in each scenario, in scenario order
     * @param meanTravelMinutes       mean over scenarios of total passenger time (platform wait + on board)
     * @param meanRecoverySeconds     mean headway recovery time (see {@link HeadwayRecovery}) over the scenarios the
     *                                strategy recovered from within the horizon; {@code null} if it recovered from none
     * @param unrecoveredScenarios    scenarios whose headways were still disrupted at the horizon
     * @param meanShortTurns          trains reversed early per scenario, on average (0 unless the strategy
     *                                short-turns late trains)
     * @param shortTurnsByStation     short turns over all scenarios by the station where they happened, most first
     *                                (ties by station id)
     */
    public record StrategyRanking(int rank, String strategy, double meanWaitSeconds, double p95WaitSeconds,
                                  double meanTravelMinutes, Double meanRecoverySeconds, int unrecoveredScenarios,
                                  double meanShortTurns, Map<String, Integer> shortTurnsByStation,
                                  List<Double> waitSecondsPerScenario) {

        /** The same ranking at position {@code newRank}. */
        public StrategyRanking withRank(int newRank) {
            return new StrategyRanking(newRank, strategy, meanWaitSeconds, p95WaitSeconds, meanTravelMinutes,
                    meanRecoverySeconds, unrecoveredScenarios, meanShortTurns, shortTurnsByStation,
                    waitSecondsPerScenario);
        }
    }
}
