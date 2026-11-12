package com.metromind.incident;

import java.util.List;

/**
 * Result of a counterfactual delay analysis: the same network is simulated twice from the same state,
 * once with the incident and once without, for each headway strategy.
 *
 * <p>{@code totalExtraPassengerWaitMinutes} is relative to the same strategy's baseline (how much the incident
 * hurts); {@code totalPassengerWaitMinutes} is absolute over the horizon and is what strategies are ranked by.
 */
public record DelayImpactReport(String trainId, double injectedDelaySeconds, double horizonSeconds,
                                List<StrategyOutcome> outcomes, String bestStrategy) {

    public record StrategyOutcome(String strategy, int trainsAffected, double maxTrainDelaySeconds,
                                  double totalExtraPassengerWaitMinutes, double totalPassengerWaitMinutes,
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
