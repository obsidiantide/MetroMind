package com.metromind.incident;

import com.metromind.network.Station;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.TrainState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers "train X is delayed by N seconds: how does it affect the rest of the network?".
 *
 * <p>For every headway strategy it forks the live engine twice (baseline / with incident), runs both for the
 * horizon and diffs train arrival times, passenger waiting and on-board time. The strategy with the smallest
 * total passenger travel time (platform wait + ride) in the incident run is reported as the best one.
 */
public final class DelayPropagationAnalyzer {

    static final double AFFECTED_THRESHOLD_SECONDS = 15;
    private static final double DT = 1.0;
    /** Interval at which both runs' headway CV is sampled to measure recovery. */
    static final double RECOVERY_SAMPLE_SECONDS = 30;

    public DelayImpactReport analyze(SimulationEngine live, String trainId, double delaySeconds,
                                     double horizonSeconds, List<HeadwayStrategy> strategies) {
        live.train(trainId); // validate early
        List<DelayImpactReport.StrategyOutcome> outcomes = new ArrayList<>();
        for (HeadwayStrategy strategy : strategies) {
            outcomes.add(runCounterfactual(live, trainId, delaySeconds, horizonSeconds, strategy));
        }
        String best = outcomes.stream()
                .min(Comparator.comparingDouble(DelayImpactReport.StrategyOutcome::totalPassengerTravelMinutes))
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
        Map<String, Integer> startArrivals = new HashMap<>();
        live.trains().forEach(t -> startArrivals.put(t.id(), t.arrivals().size()));
        perturbed.injectDelay(trainId, delaySeconds);
        HeadwayRecovery.Result recovery = runSampled(baseline, perturbed, horizon);

        List<DelayImpactReport.TrainImpact> trainImpacts = new ArrayList<>();
        // Pair trains by id: a strategy that dispatches or withdraws trains can leave the two runs with different
        // fleets. A train that only one run has in service at the horizon cannot be compared and is left out.
        Map<String, TrainState> pertTrains = new HashMap<>();
        perturbed.trains().forEach(t -> pertTrains.put(t.id(), t));
        for (TrainState base : baseline.trains()) {
            TrainState pert = pertTrains.get(base.id());
            if (pert == null) {
                continue;
            }
            int start = startArrivals.getOrDefault(base.id(), 0);
            List<Double> b = base.arrivals();
            List<Double> p = pert.arrivals();
            double extra = extraDelay(b.subList(start, b.size()), p.subList(start, p.size()), perturbed.simTime());
            trainImpacts.add(new DelayImpactReport.TrainImpact(base.id(), extra));
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
                totalAbs + (perturbed.passengerRideSeconds() - live.passengerRideSeconds()) / 60.0,
                perturbed.snapshot().metrics().headwayCoefficientOfVariation(), recovery.recoverySeconds(),
                recovery.peakCvExcess(), trainImpacts, stationImpacts);
    }

    /** Runs both engines to the horizon, sampling their headway CV to measure how the incident run recovers. */
    static HeadwayRecovery.Result runSampled(SimulationEngine baseline, SimulationEngine incident, double horizon) {
        List<Double> baseCv = HeadwayRecovery.sampleHeadwayCv(baseline, horizon, RECOVERY_SAMPLE_SECONDS, DT);
        List<Double> incidentCv = HeadwayRecovery.sampleHeadwayCv(incident, horizon, RECOVERY_SAMPLE_SECONDS, DT);
        return HeadwayRecovery.measure(baseCv, incidentCv, RECOVERY_SAMPLE_SECONDS);
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
