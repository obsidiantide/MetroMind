package com.metromind.incident;

import java.util.List;

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
 */
public record DelayImpactReport(String trainId, double injectedDelaySeconds, double horizonSeconds,
                                List<StrategyOutcome> outcomes, String bestStrategy) {

    public record StrategyOutcome(String strategy, int trainsAffected, double maxTrainDelaySeconds,
                                  double totalExtraPassengerWaitMinutes, double totalPassengerWaitMinutes,
                                  double totalExtraRideMinutes, double totalExtraTravelMinutes,
                                  double totalPassengerTravelMinutes,
                                  double headwayCvAtHorizon,
                                  List<TrainImpact> trains, List<StationImpact> stations) {
    }

    /** @param extraDelaySeconds how much later this train reaches its stops compared with the baseline */
    public record TrainImpact(String trainId, double extraDelaySeconds) {
    }

    /** @param extraWaitMinutes additional passenger-minutes spent waiting at this station */
    public record StationImpact(String stationId, double extraWaitMinutes, double peakWaitingDelta) {
    }
}
