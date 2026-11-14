package com.metromind.incident;

import com.metromind.network.Station;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.TrainState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Ranks headway strategies over many incidents rather than one: draws {@code N} random incident scenarios (a
 * train delayed or broken down for a random time) from a seed, runs every strategy against every scenario from
 * the same copy of the live network, and compares the passenger wait they lead to.
 *
 * <p>Everything is deterministic: scenarios come from {@link Random} with the given seed (its algorithm is fixed by
 * the Java specification) and the engine itself has no randomness, so a seed and a starting state always produce
 * the same report.
 */
public final class StrategyBenchmark {

    static final double MIN_DELAY_SECONDS = 60;
    static final double MAX_DELAY_SECONDS = 600;
    static final double MIN_REPAIR_SECONDS = 300;
    static final double MAX_REPAIR_SECONDS = 1200;
    private static final double DT = 1.0;

    public BenchmarkReport run(SimulationEngine live, int scenarioCount, long seed, double horizonSeconds,
                               List<HeadwayStrategy> strategies) {
        if (scenarioCount < 1) {
            throw new IllegalArgumentException("A benchmark needs at least one scenario");
        }
        List<BenchmarkReport.Scenario> scenarios = drawScenarios(live, scenarioCount, seed);
        // Measured once up front: taking a snapshot updates the engine's overload alerts.
        double startBoarded = live.snapshot().metrics().passengersServed();
        double startWait = totalWaitSeconds(live);
        double startRide = live.passengerRideSeconds();
        List<BenchmarkReport.StrategyRanking> unranked = new ArrayList<>();
        for (HeadwayStrategy strategy : strategies) {
            List<Double> waits = new ArrayList<>();
            double travelMinutes = 0;
            for (BenchmarkReport.Scenario scenario : scenarios) {
                SimulationEngine run = live.copy();
                run.setStrategy(strategy);
                inject(run, scenario);
                run.run(horizonSeconds, DT);
                double boarded = run.snapshot().metrics().passengersServed() - startBoarded;
                double waited = totalWaitSeconds(run) - startWait;
                waits.add(boarded > 0 ? waited / boarded : 0);
                travelMinutes += (waited + run.passengerRideSeconds() - startRide) / 60.0;
            }
            double mean = waits.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            unranked.add(new BenchmarkReport.StrategyRanking(0, strategy.name(), mean, percentile(waits, 0.95),
                    travelMinutes / scenarios.size(), List.copyOf(waits)));
        }
        unranked.sort(Comparator.comparingDouble(BenchmarkReport.StrategyRanking::meanWaitSeconds)
                .thenComparingDouble(BenchmarkReport.StrategyRanking::p95WaitSeconds)
                .thenComparing(BenchmarkReport.StrategyRanking::strategy));
        List<BenchmarkReport.StrategyRanking> rankings = new ArrayList<>();
        for (int i = 0; i < unranked.size(); i++) {
            BenchmarkReport.StrategyRanking r = unranked.get(i);
            rankings.add(new BenchmarkReport.StrategyRanking(i + 1, r.strategy(), r.meanWaitSeconds(),
                    r.p95WaitSeconds(), r.meanTravelMinutes(), r.waitSecondsPerScenario()));
        }
        return new BenchmarkReport(seed, horizonSeconds, scenarios, rankings);
    }

    /** Draws the scenarios: a train of the live fleet, a kind of incident and a duration in its range. */
    static List<BenchmarkReport.Scenario> drawScenarios(SimulationEngine live, int count, long seed) {
        List<String> trainIds = live.trains().stream().map(TrainState::id).toList();
        Random random = new Random(seed);
        List<BenchmarkReport.Scenario> scenarios = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String trainId = trainIds.get(random.nextInt(trainIds.size()));
            boolean breakdown = random.nextBoolean();
            double seconds = breakdown
                    ? MIN_REPAIR_SECONDS + random.nextInt((int) (MAX_REPAIR_SECONDS - MIN_REPAIR_SECONDS) + 1)
                    : MIN_DELAY_SECONDS + random.nextInt((int) (MAX_DELAY_SECONDS - MIN_DELAY_SECONDS) + 1);
            scenarios.add(new BenchmarkReport.Scenario(
                    breakdown ? BenchmarkReport.IncidentKind.BREAKDOWN : BenchmarkReport.IncidentKind.DELAY,
                    trainId, seconds));
        }
        return scenarios;
    }

    private static void inject(SimulationEngine engine, BenchmarkReport.Scenario scenario) {
        switch (scenario.kind()) {
            case DELAY -> engine.injectDelay(scenario.trainId(), scenario.seconds());
            case BREAKDOWN -> engine.injectBreakdown(scenario.trainId(), scenario.seconds());
        }
    }

    /** Passenger-seconds waited at all stations so far, on platforms or at entrances. */
    private static double totalWaitSeconds(SimulationEngine engine) {
        double total = 0;
        for (Station s : engine.network().stations()) {
            total += engine.stationWaitSeconds(s.id());
        }
        return total;
    }

    /** Nearest-rank percentile: the smallest value with at least {@code p} of the values at or below it. */
    static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }
}
