package com.metromind.incident;

import com.metromind.sim.ShortTurn;
import com.metromind.sim.SkippedStop;

import java.util.List;
import java.util.Map;

/**
 * Result of a counterfactual delay analysis: the same network is simulated twice from the same state,
 * once with the incident and once without, for each headway strategy.
 *
 * <p>{@code totalExtraPassengerWaitMinutes} is relative to the same strategy's baseline (how much the incident
 * hurts); {@code totalPassengerWaitMinutes} is absolute over the horizon. {@code totalExtraRideMinutes} is the
 * extra time passengers spend on board (e.g. stuck behind the incident train), so {@code totalExtraTravelMinutes}
 * = extra wait + extra ride is the full cost of the incident.
 *
 * <p>Strategies are ranked by {@code totalPassengerTravelMinutes}: absolute platform wait plus time on board over
 * the horizon. Ranking by platform wait alone would favour strategies that hold trains, since a held train
 * moves waiting from the platform onto the train without making anyone's journey shorter.
 *
 * <p>{@code headwayRecoverySeconds} is how long after the incident the headways were back to the baseline's
 * regularity for good (see {@link HeadwayRecovery}); {@code null} when they were still disrupted at the horizon.
 * {@code peakHeadwayCvExcess} is the worst bunching the incident caused, as headway CV above the baseline.
 * {@code trunkHeadwayCvAtHorizon} gives, for each branched line, the headway CV of the combined service of its
 * patterns on the shared trunk at the horizon; {@code headwayCvAtHorizon} judges each pattern on its own, so trains
 * of two patterns running as a pair on the trunk only show up in the former.
 *
 * <p>{@code shortTurns} lists the trains the strategy reversed early during the incident run (only strategies that
 * short-turn late trains make any), oldest first, so the report shows where service was cut back and when.
 * {@code skippedStops} likewise lists the stations late trains ran through (only strategies that skip stops make
 * any), with the passengers each skip carried past their stop.
 */
public record DelayImpactReport(String trainId, double injectedDelaySeconds, double horizonSeconds,
                                List<StrategyOutcome> outcomes, String bestStrategy) {

    public record StrategyOutcome(String strategy, int trainsAffected, double maxTrainDelaySeconds,
                                  double totalExtraPassengerWaitMinutes, double totalPassengerWaitMinutes,
                                  double totalExtraRideMinutes, double totalExtraTravelMinutes,
                                  double totalPassengerTravelMinutes,
                                  double headwayCvAtHorizon, Map<String, Double> trunkHeadwayCvAtHorizon,
                                  Double headwayRecoverySeconds,
                                  double peakHeadwayCvExcess, List<ShortTurn> shortTurns,
                                  List<SkippedStop> skippedStops,
                                  List<TrainImpact> trains, List<StationImpact> stations) {
    }

    /** @param extraDelaySeconds how much later this train reaches its stops compared with the baseline */
    public record TrainImpact(String trainId, double extraDelaySeconds) {
    }

    /** @param extraWaitMinutes additional passenger-minutes spent waiting at this station */
    public record StationImpact(String stationId, double extraWaitMinutes, double peakWaitingDelta) {
    }
}
