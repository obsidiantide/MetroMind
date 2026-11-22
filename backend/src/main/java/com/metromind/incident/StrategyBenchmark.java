package com.metromind.incident;

import com.metromind.network.Line;
import com.metromind.network.Station;
import com.metromind.sim.ClosureResponse;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.DwellVariability;
import com.metromind.sim.ReliefOverrun;
import com.metromind.sim.RunTimeVariability;
import com.metromind.sim.ShortTurn;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.SkippedStop;
import com.metromind.sim.TrainState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.TreeMap;

/**
 * Ranks headway strategies over many incidents rather than one: draws {@code N} random incident scenarios (a train
 * delayed, broken down or held at a platform by a door fault, a track section or a station closed, a line's
 * traction power lost, each for a random time) from a seed, runs every
 * strategy against every scenario from the same copy of the live network, and compares the passenger wait or
 * total travel time they lead to.
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
    static final double MIN_DOOR_FAULT_SECONDS = 30;
    static final double MAX_DOOR_FAULT_SECONDS = 180;
    static final double MIN_CLOSURE_SECONDS = 300;
    static final double MAX_CLOSURE_SECONDS = 1200;
    static final double MIN_POWER_FAILURE_SECONDS = 120;
    static final double MAX_POWER_FAILURE_SECONDS = 900;
    /** Longest dead feeder section drawn, in sections between stations. */
    static final int MAX_FEEDER_SECTIONS = 3;
    private static final double DT = 1.0;
    static final double RECOVERY_SAMPLE_SECONDS = 30;

    public BenchmarkReport run(SimulationEngine live, int scenarioCount, long seed, double horizonSeconds,
                               List<HeadwayStrategy> strategies) {
        return run(live, scenarioCount, seed, horizonSeconds, strategies,
                EnumSet.allOf(BenchmarkReport.IncidentKind.class));
    }

    /** As {@link #run(SimulationEngine, int, long, double, List)}, drawing only incidents of the given kinds. */
    public BenchmarkReport run(SimulationEngine live, int scenarioCount, long seed, double horizonSeconds,
                               List<HeadwayStrategy> strategies, Set<BenchmarkReport.IncidentKind> kinds) {
        return run(live, scenarioCount, seed, horizonSeconds, strategies, kinds,
                BenchmarkReport.RankingMetric.WAIT_PER_BOARDING);
    }

    /**
     * Notified after every simulated run: each strategy runs a baseline and then every scenario, so a benchmark makes
     * {@code strategies × (scenarios + 1)} runs in total.
     */
    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (completedRuns, totalRuns) -> { };

        void onProgress(int completedRuns, int totalRuns);
    }

    /** Simulated runs a benchmark of {@code scenarios} over {@code strategies} makes, baselines included. */
    public static int totalRuns(int scenarios, int strategies) {
        return strategies * (scenarios + 1);
    }

    /** As {@link #run(SimulationEngine, int, long, double, List, Set)}, ranking strategies by {@code metric}. */
    public BenchmarkReport run(SimulationEngine live, int scenarioCount, long seed, double horizonSeconds,
                               List<HeadwayStrategy> strategies, Set<BenchmarkReport.IncidentKind> kinds,
                               BenchmarkReport.RankingMetric metric) {
        return run(live, scenarioCount, seed, horizonSeconds, strategies, kinds, metric, ProgressListener.NONE);
    }

    /**
     * As {@link #run(SimulationEngine, int, long, double, List, Set, BenchmarkReport.RankingMetric)}, reporting
     * progress to {@code progress} after every run. Run times and dwells vary as much as on the live engine.
     */
    public BenchmarkReport run(SimulationEngine live, int scenarioCount, long seed, double horizonSeconds,
                               List<HeadwayStrategy> strategies, Set<BenchmarkReport.IncidentKind> kinds,
                               BenchmarkReport.RankingMetric metric, ProgressListener progress) {
        return run(live, scenarioCount, seed, horizonSeconds, strategies, kinds, metric,
                live.runTimeVariability().sigma(), progress);
    }

    /**
     * The full benchmark with run times varying by {@code runTimeVariability} and dwells as much as on the live
     * engine.
     */
    public BenchmarkReport run(SimulationEngine live, int scenarioCount, long seed, double horizonSeconds,
                               List<HeadwayStrategy> strategies, Set<BenchmarkReport.IncidentKind> kinds,
                               BenchmarkReport.RankingMetric metric, double runTimeVariability,
                               ProgressListener progress) {
        return run(live, scenarioCount, seed, horizonSeconds, strategies, kinds, metric, runTimeVariability,
                live.dwellVariability().sigma(), progress);
    }

    /** The full benchmark with late reliefs as often and as long as on the live engine. */
    public BenchmarkReport run(SimulationEngine live, int scenarioCount, long seed, double horizonSeconds,
                               List<HeadwayStrategy> strategies, Set<BenchmarkReport.IncidentKind> kinds,
                               BenchmarkReport.RankingMetric metric, double runTimeVariability,
                               double dwellVariability, ProgressListener progress) {
        return run(live, scenarioCount, seed, horizonSeconds, strategies, kinds, metric, runTimeVariability,
                dwellVariability, live.reliefOverrun(), progress);
    }

    /**
     * The full benchmark. Every run, baselines included, draws its run times with spread
     * {@code runTimeVariability}, its dwells with spread {@code dwellVariability} and its late reliefs with the share
     * and mean of {@code reliefOverrun} (its seed is ignored) from the benchmark {@code seed}: a seed gives the same
     * everyday irregularity in every run (common random numbers), so strategies are compared on the same "day" and
     * differences come from the strategies and incidents, and the report stays reproducible whatever the live
     * engine's own seed. Lines with their own late reliefs keep them.
     */
    public BenchmarkReport run(SimulationEngine live, int scenarioCount, long seed, double horizonSeconds,
                               List<HeadwayStrategy> strategies, Set<BenchmarkReport.IncidentKind> kinds,
                               BenchmarkReport.RankingMetric metric, double runTimeVariability,
                               double dwellVariability, ReliefOverrun reliefOverrun, ProgressListener progress) {
        RunTimeVariability variability = new RunTimeVariability(runTimeVariability, seed);
        DwellVariability dwells = new DwellVariability(dwellVariability, seed);
        ReliefOverrun reliefs = reliefOverrun.withSeed(seed);
        if (scenarioCount < 1) {
            throw new IllegalArgumentException("A benchmark needs at least one scenario");
        }
        List<BenchmarkReport.Scenario> scenarios = drawScenarios(live, scenarioCount, seed, kinds);
        // Measured once up front: taking a snapshot updates the engine's overload alerts.
        double startBoarded = live.snapshot().metrics().passengersServed();
        double startWait = totalWaitSeconds(live);
        double startRide = live.passengerRideSeconds();
        int totalRuns = totalRuns(scenarios.size(), strategies.size());
        int completedRuns = 0;
        List<BenchmarkReport.StrategyRanking> unranked = new ArrayList<>();
        for (HeadwayStrategy strategy : strategies) {
            List<Double> waits = new ArrayList<>();
            List<Double> travels = new ArrayList<>();
            SimulationEngine baseline = live.copy();
            baseline.setStrategy(strategy);
            baseline.setRunTimeVariability(variability);
            baseline.setDwellVariability(dwells);
            baseline.setReliefOverrun(reliefs);
            List<Double> baselineCv = HeadwayRecovery.sampleHeadwayCv(baseline, horizonSeconds,
                    RECOVERY_SAMPLE_SECONDS, DT);
            progress.onProgress(++completedRuns, totalRuns);
            double recoverySum = 0;
            int recovered = 0;
            Map<String, Integer> turnsByStation = new HashMap<>();
            int turns = 0;
            Map<String, Integer> skipsByStation = new HashMap<>();
            int skips = 0;
            double carriedPast = 0;
            Map<String, Double> trunkCvSum = new TreeMap<>();
            for (BenchmarkReport.Scenario scenario : scenarios) {
                SimulationEngine run = live.copy();
                run.setStrategy(strategy);
                run.setRunTimeVariability(variability);
                run.setDwellVariability(dwells);
                run.setReliefOverrun(reliefs);
                scenario.applyTo(run);
                double injectedAt = run.simTime();
                HeadwayRecovery.Result recovery = HeadwayRecovery.measure(baselineCv,
                        HeadwayRecovery.sampleHeadwayCv(run, horizonSeconds, RECOVERY_SAMPLE_SECONDS, DT),
                        RECOVERY_SAMPLE_SECONDS);
                if (recovery.recovered()) {
                    recoverySum += recovery.recoverySeconds();
                    recovered++;
                }
                double boarded = run.snapshot().metrics().passengersServed() - startBoarded;
                double waited = totalWaitSeconds(run) - startWait;
                waits.add(boarded > 0 ? waited / boarded : 0);
                travels.add((waited + run.passengerRideSeconds() - startRide) / 60.0);
                run.trunkHeadwayCv().forEach((line, cv) -> trunkCvSum.merge(line, cv, Double::sum));
                for (ShortTurn turn : run.shortTurnsSince(injectedAt)) {
                    turnsByStation.merge(turn.stationId(), 1, Integer::sum);
                    turns++;
                }
                for (SkippedStop skip : run.skippedStopsSince(injectedAt)) {
                    skipsByStation.merge(skip.stationId(), 1, Integer::sum);
                    skips++;
                    carriedPast += skip.passengersCarriedPast();
                }
                progress.onProgress(++completedRuns, totalRuns);
            }
            double mean = waits.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            unranked.add(new BenchmarkReport.StrategyRanking(0, strategy.name(), mean, percentile(waits, 0.95),
                    travels.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                    percentile(travels, 0.95), recovered > 0 ? recoverySum / recovered : null,
                    scenarios.size() - recovered, meanOver(trunkCvSum, scenarios.size()),
                    (double) turns / scenarios.size(), mostFirst(turnsByStation),
                    (double) skips / scenarios.size(), carriedPast / scenarios.size(), mostFirst(skipsByStation),
                    List.copyOf(waits)));
        }
        unranked.sort(order(metric));
        List<BenchmarkReport.StrategyRanking> rankings = new ArrayList<>();
        for (int i = 0; i < unranked.size(); i++) {
            rankings.add(unranked.get(i).withRank(i + 1));
        }
        return new BenchmarkReport(seed, horizonSeconds, metric, scenarios, rankings, runTimeVariability,
                dwellVariability, reliefs.share(), reliefs.meanSeconds());
    }

    /** Each line's sum divided by {@code count}, in line order. */
    private static Map<String, Double> meanOver(Map<String, Double> sums, int count) {
        Map<String, Double> means = new LinkedHashMap<>();
        sums.forEach((line, sum) -> means.put(line, sum / count));
        return means;
    }

    /** Best first by the metric's mean, then its p95, then strategy name. */
    static Comparator<BenchmarkReport.StrategyRanking> order(BenchmarkReport.RankingMetric metric) {
        Comparator<BenchmarkReport.StrategyRanking> byMetric = switch (metric) {
            case WAIT_PER_BOARDING -> Comparator.comparingDouble(BenchmarkReport.StrategyRanking::meanWaitSeconds)
                    .thenComparingDouble(BenchmarkReport.StrategyRanking::p95WaitSeconds);
            case TRAVEL_TIME -> Comparator.comparingDouble(BenchmarkReport.StrategyRanking::meanTravelMinutes)
                    .thenComparingDouble(BenchmarkReport.StrategyRanking::p95TravelMinutes);
        };
        return byMetric.thenComparing(BenchmarkReport.StrategyRanking::strategy);
    }

    static List<BenchmarkReport.Scenario> drawScenarios(SimulationEngine live, int count, long seed) {
        return drawScenarios(live, count, seed, EnumSet.allOf(BenchmarkReport.IncidentKind.class));
    }

    /**
     * Draws the scenarios: for each one a kind among {@code kinds} (in declaration order, so a seed always maps to
     * the same incidents), then its subject (a train of the live fleet, a pair of adjacent stations of a line, a
     * station, or a line) and a duration in the kind's range. A closed section gets a random response; half the
     * power failures cut a whole line and half only a feeder section of one to {@value #MAX_FEEDER_SECTIONS}
     * sections along it.
     */
    static List<BenchmarkReport.Scenario> drawScenarios(SimulationEngine live, int count, long seed,
                                                        Set<BenchmarkReport.IncidentKind> kinds) {
        if (kinds.isEmpty()) {
            throw new IllegalArgumentException("A benchmark needs at least one kind of incident");
        }
        List<BenchmarkReport.IncidentKind> pool = List.copyOf(EnumSet.copyOf(kinds));
        List<String> trainIds = live.trains().stream().map(TrainState::id).toList();
        List<Line> lines = live.network().lines();
        List<Station> stations = live.network().stations();
        Random random = new Random(seed);
        List<BenchmarkReport.Scenario> scenarios = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BenchmarkReport.IncidentKind kind = pool.get(random.nextInt(pool.size()));
            scenarios.add(switch (kind) {
                case DELAY -> BenchmarkReport.Scenario.train(kind, trainIds.get(random.nextInt(trainIds.size())),
                        between(random, MIN_DELAY_SECONDS, MAX_DELAY_SECONDS));
                case BREAKDOWN -> BenchmarkReport.Scenario.train(kind,
                        trainIds.get(random.nextInt(trainIds.size())),
                        between(random, MIN_REPAIR_SECONDS, MAX_REPAIR_SECONDS));
                case SECTION_CLOSURE -> {
                    Line line = lines.get(random.nextInt(lines.size()));
                    int from = random.nextInt(line.sectionCount());
                    ClosureResponse response = ClosureResponse.values()[random.nextInt(ClosureResponse.values().length)];
                    yield BenchmarkReport.Scenario.sectionClosure(line.id(), line.stationIds().get(from),
                            line.stationIds().get(line.sectionEnd(from)), response,
                            between(random, MIN_CLOSURE_SECONDS, MAX_CLOSURE_SECONDS));
                }
                case STATION_CLOSURE -> BenchmarkReport.Scenario.stationClosure(
                        stations.get(random.nextInt(stations.size())).id(),
                        between(random, MIN_CLOSURE_SECONDS, MAX_CLOSURE_SECONDS));
                case DOOR_FAULT -> BenchmarkReport.Scenario.train(kind,
                        trainIds.get(random.nextInt(trainIds.size())),
                        between(random, MIN_DOOR_FAULT_SECONDS, MAX_DOOR_FAULT_SECONDS));
                case POWER_FAILURE -> {
                    Line line = lines.get(random.nextInt(lines.size()));
                    if (!random.nextBoolean()) {
                        yield BenchmarkReport.Scenario.powerFailure(line.id(),
                                between(random, MIN_POWER_FAILURE_SECONDS, MAX_POWER_FAILURE_SECONDS));
                    }
                    // A feeder section of one to MAX_FEEDER_SECTIONS sections along the main route (round a ring).
                    List<String> ids = line.stationIds();
                    int from = random.nextInt(line.sectionCount());
                    int length = 1 + random.nextInt(MAX_FEEDER_SECTIONS);
                    int to = line.ring() ? (from + length) % ids.size() : Math.min(from + length, ids.size() - 1);
                    yield BenchmarkReport.Scenario.sectionalPowerFailure(line.id(), ids.get(from), ids.get(to),
                            between(random, MIN_POWER_FAILURE_SECONDS, MAX_POWER_FAILURE_SECONDS));
                }
            });
        }
        return scenarios;
    }

    /** A whole number of seconds in {@code [min, max]}. */
    private static double between(Random random, double min, double max) {
        return min + random.nextInt((int) (max - min) + 1);
    }

    /** Counts ordered from highest to lowest, ties by key, in an insertion-ordered map. */
    static Map<String, Integer> mostFirst(Map<String, Integer> counts) {
        Map<String, Integer> ordered = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return Collections.unmodifiableMap(ordered);
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
