package com.metromind.incident;

import com.metromind.network.Station;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.TrainState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Answers "train X is delayed by N seconds: how does it affect the rest of the network?".
 *
 * <p>For every headway strategy it forks the live engine twice (baseline / with incident), runs both for the
 * horizon and diffs train arrival times and passenger waiting time. The strategy with the smallest total
 * passenger wait in the incident run is reported as the best one.
 */
public final class DelayPropagationAnalyzer {

    static final double AFFECTED_THRESHOLD_SECONDS = 15;
    private static final double DT = 1.0;

    public DelayImpactReport analyze(SimulationEngine live, String trainId, double delaySeconds,
                                     double horizonSeconds, List<HeadwayStrategy> strategies) {
        live.train(trainId); // validate early
        List<DelayImpactReport.StrategyOutcome> outcomes = new ArrayList<>();
        for (HeadwayStrategy strategy : strategies) {
            outcomes.add(runCounterfactual(live, trainId, delaySeconds, horizonSeconds, strategy));
        }
        String best = outcomes.stream()
                .min(Comparator.comparingDouble(DelayImpactReport.StrategyOutcome::totalPassengerWaitMinutes))
                .map(DelayImpactReport.StrategyOutcome::strategy).orElse(null);
        return new DelayImpactReport(trainId, delaySeconds, horizonSeconds, outcomes, best);
    }

    private DelayImpactReport.StrategyOutcome runCounterfactual(SimulationEngine live, String trainId,
                                                                double delaySeconds, double horizon,
                                                                HeadwayStrategy strategy) {
        SimulationEngine baseline = live.copy();
        SimulationEngine perturbed = live.copy();
        for (SimulationEngine e : List.of(baseline, perturbed)) {
            e.setStrategy(strategy);
            e.setRecordArrivals(true);
        }
        int[] startArrivals = live.trains().stream().mapToInt(t -> t.arrivals().size()).toArray();
        perturbed.injectDelay(trainId, delaySeconds);
        baseline.run(horizon, DT);
        perturbed.run(horizon, DT);

        List<DelayImpactReport.TrainImpact> trainImpacts = new ArrayList<>();
        List<TrainState> baseTrains = baseline.trains();
        List<TrainState> pertTrains = perturbed.trains();
        for (int i = 0; i < baseTrains.size(); i++) {
            List<Double> b = baseTrains.get(i).arrivals();
            List<Double> p = pertTrains.get(i).arrivals();
            double extra = extraDelay(b.subList(startArrivals[i], b.size()), p.subList(startArrivals[i], p.size()),
                    perturbed.simTime());
            trainImpacts.add(new DelayImpactReport.TrainImpact(baseTrains.get(i).id(), extra));
        }
        trainImpacts.sort(Comparator.comparingDouble(DelayImpactReport.TrainImpact::extraDelaySeconds).reversed());

        List<DelayImpactReport.StationImpact> stationImpacts = new ArrayList<>();
        double totalExtra = 0;
        double totalAbs = 0;
        for (Station s : live.network().stations()) {
            double extraMin = (perturbed.stationWaitSeconds(s.id()) - baseline.stationWaitSeconds(s.id())) / 60.0;
            double peakDelta = perturbed.stationPeakWaiting(s.id()) - baseline.stationPeakWaiting(s.id());
            totalExtra += extraMin;
            totalAbs += (perturbed.stationWaitSeconds(s.id()) - live.stationWaitSeconds(s.id())) / 60.0;
            stationImpacts.add(new DelayImpactReport.StationImpact(s.id(), extraMin, peakDelta));
        }
        stationImpacts.sort(Comparator.comparingDouble(DelayImpactReport.StationImpact::extraWaitMinutes).reversed());

        int affected = (int) trainImpacts.stream()
                .filter(t -> t.extraDelaySeconds() >= AFFECTED_THRESHOLD_SECONDS).count();
        double max = trainImpacts.isEmpty() ? 0 : trainImpacts.get(0).extraDelaySeconds();
        double extraRide = (perturbed.passengerRideSeconds() - baseline.passengerRideSeconds()) / 60.0;
        return new DelayImpactReport.StrategyOutcome(strategy.name(), affected, max, totalExtra, totalAbs,
                extraRide, totalExtra + extraRide,
                perturbed.snapshot().metrics().headwayCoefficientOfVariation(), trainImpacts, stationImpacts);
    }

    /**
     * Largest lag between matching arrivals. If the perturbed train made fewer stops, the missing stop counts
     * as "at least horizon end − baseline arrival" late.
     */
    static double extraDelay(List<Double> baseline, List<Double> perturbed, double horizonEnd) {
        double extra = 0;
        int common = Math.min(baseline.size(), perturbed.size());
        for (int i = 0; i < common; i++) {
            extra = Math.max(extra, perturbed.get(i) - baseline.get(i));
        }
        if (perturbed.size() < baseline.size()) {
            extra = Math.max(extra, horizonEnd - baseline.get(perturbed.size()));
        }
        return Math.max(0, extra);
    }
}
