package com.metromind.incident;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.metromind.sim.ClosureResponse;
import com.metromind.sim.SimulationEngine;

import java.util.List;
import java.util.Map;

/**
 * Result of benchmarking headway strategies against the same set of random incident scenarios, all started from
 * one snapshot of the network.
 *
 * <p>For each scenario and strategy the benchmark measures the mean platform wait per boarding over the horizon:
 * passenger-seconds spent waiting at stations divided by the passengers who boarded a train. {@code meanWaitSeconds}
 * averages that over the scenarios and {@code p95WaitSeconds} is its 95th percentile (nearest rank), the
 * "bad day" a strategy has to live with. With the default {@link RankingMetric#WAIT_PER_BOARDING} rankings are by
 * that mean, then its p95, then name; with {@link RankingMetric#TRAVEL_TIME} they are by mean total passenger time
 * (platform wait + on board), then its p95, then name. Either way ties are deterministic.
 *
 * @param seed       seed the scenarios were drawn with; the same seed and starting state give the same report
 * @param metric     what the rankings are ordered by
 * @param scenarios  the incidents every strategy was run against
 * @param rankings   one entry per strategy, best first
 * @param runTimeVariability spread of run times every run was drawn with (see
 *                   {@link com.metromind.sim.RunTimeVariability}), seeded by {@code seed}; 0 for nominal run times
 * @param dwellVariability spread of dwells every run was drawn with (see
 *                   {@link com.metromind.sim.DwellVariability}), seeded by {@code seed}; 0 for passenger-driven dwells
 * @param reliefOverrunShare share of crew changes whose relief driver was late in every run (see
 *                   {@link com.metromind.sim.ReliefOverrun}), seeded by {@code seed}; 0 when swaps ran to plan (and in
 *                   reports stored before late reliefs were reported); lines with their own share keep it
 * @param reliefOverrunSeconds mean overrun of a late relief, seconds
 */
public record BenchmarkReport(long seed, double horizonSeconds, RankingMetric metric, List<Scenario> scenarios,
                              List<StrategyRanking> rankings, double runTimeVariability, double dwellVariability,
                              double reliefOverrunShare, double reliefOverrunSeconds) {

    /** Report of a benchmark run whose crew changes ran to plan. */
    public BenchmarkReport(long seed, double horizonSeconds, RankingMetric metric, List<Scenario> scenarios,
                           List<StrategyRanking> rankings, double runTimeVariability, double dwellVariability) {
        this(seed, horizonSeconds, metric, scenarios, rankings, runTimeVariability, dwellVariability, 0, 0);
    }

    /** Report of a benchmark run with passenger-driven dwells. */
    public BenchmarkReport(long seed, double horizonSeconds, RankingMetric metric, List<Scenario> scenarios,
                           List<StrategyRanking> rankings, double runTimeVariability) {
        this(seed, horizonSeconds, metric, scenarios, rankings, runTimeVariability, 0);
    }

    /** Report of a benchmark run to nominal run times. */
    public BenchmarkReport(long seed, double horizonSeconds, RankingMetric metric, List<Scenario> scenarios,
                           List<StrategyRanking> rankings) {
        this(seed, horizonSeconds, metric, scenarios, rankings, 0, 0);
    }

    /** What strategies are ranked by. */
    public enum RankingMetric {
        /** Mean platform wait per boarding: how long the average passenger stood on a platform. */
        WAIT_PER_BOARDING,
        /** Total passenger time (platform wait + on board): also counts time lost to holding, detours and skips. */
        TRAVEL_TIME
    }

    public enum IncidentKind { DELAY, BREAKDOWN, SECTION_CLOSURE, STATION_CLOSURE, DOOR_FAULT, POWER_FAILURE }

    /**
     * One incident injected at the start of the horizon. Only the fields its kind needs are set (the others are
     * null and left out of the JSON): {@code trainId} for a delay, breakdown or door fault; {@code lineId},
     * {@code fromStationId}, {@code toStationId} and {@code response} for a track-section closure; {@code stationId}
     * for a station closure; {@code lineId} for a traction power failure. {@code seconds} is how long it lasts.
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

        public static Scenario powerFailure(String lineId, double seconds) {
            return new Scenario(IncidentKind.POWER_FAILURE, null, lineId, null, null, null, null, seconds);
        }

        /** Injects this incident into {@code engine} at its current time. */
        public void applyTo(SimulationEngine engine) {
            switch (kind) {
                case DELAY -> engine.injectDelay(trainId, seconds);
                case BREAKDOWN -> engine.injectBreakdown(trainId, seconds);
                case SECTION_CLOSURE -> engine.closeSection(lineId, fromStationId, toStationId, seconds, response);
                case STATION_CLOSURE -> engine.closeStation(stationId, seconds);
                case DOOR_FAULT -> engine.injectDoorFault(trainId, seconds);
                case POWER_FAILURE -> engine.cutPower(lineId, seconds);
            }
        }
    }

    /**
     * @param waitSecondsPerScenario  mean wait per boarding in each scenario, in scenario order
     * @param meanTravelMinutes       mean over scenarios of total passenger time (platform wait + on board)
     * @param p95TravelMinutes        95th percentile (nearest rank) of total passenger time over the scenarios
     * @param meanRecoverySeconds     mean headway recovery time (see {@link HeadwayRecovery}) over the scenarios the
     *                                strategy recovered from within the horizon; {@code null} if it recovered from none
     * @param unrecoveredScenarios    scenarios whose headways were still disrupted at the horizon
     * @param meanTrunkHeadwayCv      per branched line, mean over the scenarios of the headway CV of the combined
     *                                service on its shared trunk at the horizon (empty for a network without
     *                                branches; reports stored before this field existed read back empty too)
     * @param meanShortTurns          trains reversed early per scenario, on average (0 unless the strategy
     *                                short-turns late trains)
     * @param shortTurnsByStation     short turns over all scenarios by the station where they happened, most first
     *                                (ties by station id)
     * @param meanStopsSkipped        stops late trains ran through per scenario, on average (0 unless the strategy
     *                                skips stops)
     * @param meanPassengersCarriedPast passengers per scenario, on average, carried past their stop by a skip and
     *                                left to ride back from the next one
     * @param stopsSkippedByStation   skipped stops over all scenarios by station, most first (ties by station id)
     */
    public record StrategyRanking(int rank, String strategy, double meanWaitSeconds, double p95WaitSeconds,
                                  double meanTravelMinutes, double p95TravelMinutes,
                                  Double meanRecoverySeconds, int unrecoveredScenarios,
                                  Map<String, Double> meanTrunkHeadwayCv, double meanShortTurns, Map<String, Integer> shortTurnsByStation,
                                  double meanStopsSkipped, double meanPassengersCarriedPast,
                                  Map<String, Integer> stopsSkippedByStation,
                                  List<Double> waitSecondsPerScenario) {

        public StrategyRanking {
            meanTrunkHeadwayCv = meanTrunkHeadwayCv == null ? Map.of() : meanTrunkHeadwayCv;
        }

        /** The same ranking at position {@code newRank}. */
        public StrategyRanking withRank(int newRank) {
            return new StrategyRanking(newRank, strategy, meanWaitSeconds, p95WaitSeconds, meanTravelMinutes,
                    p95TravelMinutes, meanRecoverySeconds, unrecoveredScenarios, meanTrunkHeadwayCv, meanShortTurns,
                    shortTurnsByStation,
                    meanStopsSkipped, meanPassengersCarriedPast, stopsSkippedByStation, waitSecondsPerScenario);
        }
    }
}
