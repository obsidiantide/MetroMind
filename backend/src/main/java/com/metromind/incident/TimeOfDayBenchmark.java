package com.metromind.incident;

import com.metromind.network.MetroNetwork;
import com.metromind.sim.DwellVariability;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.RunTimeVariability;
import com.metromind.sim.SimulationEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Runs the {@link StrategyBenchmark} from several times of day. For each start time a fresh engine is placed on the
 * network {@link #WARM_UP_SECONDS} earlier and run uncontrolled until then, so the fleet plan has brought the
 * period's trains into service and queues reflect that time's demand; the benchmark then runs from that state.
 * Warm-ups draw run times and dwells from the benchmark seed too, so a seed reproduces the whole report.
 */
public final class TimeOfDayBenchmark {

    /** Simulated time a fresh engine runs before a period's benchmark starts. */
    public static final double WARM_UP_SECONDS = 1800;

    private final StrategyBenchmark benchmark = new StrategyBenchmark();

    /**
     * Simulated runs a benchmark of {@code periods} start times makes: each period's strategy benchmark (see
     * {@link StrategyBenchmark#totalRuns}); warm-ups are not counted.
     */
    public static int totalRuns(int periods, int scenarios, int strategies) {
        return periods * StrategyBenchmark.totalRuns(scenarios, strategies);
    }

    public TimeOfDayReport run(MetroNetwork network, List<String> startClocks, int scenarioCount, long seed,
                               double horizonSeconds, List<HeadwayStrategy> strategies,
                               Set<BenchmarkReport.IncidentKind> kinds, BenchmarkReport.RankingMetric metric,
                               double runTimeVariability, double dwellVariability) {
        return run(network, startClocks, scenarioCount, seed, horizonSeconds, strategies, kinds, metric,
                runTimeVariability, dwellVariability, StrategyBenchmark.ProgressListener.NONE);
    }

    /**
     * As {@link #run(MetroNetwork, List, int, long, double, List, Set, BenchmarkReport.RankingMetric, double,
     * double)}, reporting progress to {@code progress} after every run, counted over all periods (see
     * {@link #totalRuns}).
     */
    public TimeOfDayReport run(MetroNetwork network, List<String> startClocks, int scenarioCount, long seed,
                               double horizonSeconds, List<HeadwayStrategy> strategies,
                               Set<BenchmarkReport.IncidentKind> kinds, BenchmarkReport.RankingMetric metric,
                               double runTimeVariability, double dwellVariability,
                               StrategyBenchmark.ProgressListener progress) {
        if (startClocks.isEmpty()) {
            throw new IllegalArgumentException("A time-of-day benchmark needs at least one start time");
        }
        if (startClocks.stream().distinct().count() != startClocks.size()) {
            throw new IllegalArgumentException("Start times must not repeat");
        }
        startClocks.forEach(TimeOfDayBenchmark::startSeconds); // reject a bad start time before running any period
        int perPeriod = StrategyBenchmark.totalRuns(scenarioCount, strategies.size());
        int total = startClocks.size() * perPeriod;
        List<TimeOfDayReport.Period> periods = new ArrayList<>();
        for (String clock : startClocks) {
            int before = periods.size() * perPeriod;
            SimulationEngine engine = new SimulationEngine(network, startSeconds(clock) - WARM_UP_SECONDS,
                    HeadwayStrategy.NO_CONTROL);
            engine.setRunTimeVariability(new RunTimeVariability(runTimeVariability, seed));
            engine.setDwellVariability(new DwellVariability(dwellVariability, seed));
            engine.run(WARM_UP_SECONDS, 1);
            periods.add(new TimeOfDayReport.Period(clock, benchmark.run(engine, scenarioCount, seed, horizonSeconds,
                    strategies, kinds, metric, runTimeVariability, dwellVariability,
                    (done, runs) -> progress.onProgress(before + done, total))));
        }
        return new TimeOfDayReport(seed, horizonSeconds, metric, periods, rank(strategies, periods, metric));
    }

    /**
     * Seconds since midnight of a start time {@code HH:mm}, which must leave room for the warm-up after midnight.
     */
    static double startSeconds(String clock) {
        if (clock == null || !clock.matches("\\d{2}:\\d{2}")) {
            throw new IllegalArgumentException("Start times must be HH:mm, got " + clock);
        }
        int hours = Integer.parseInt(clock.substring(0, 2));
        int minutes = Integer.parseInt(clock.substring(3));
        if (hours > 23 || minutes > 59) {
            throw new IllegalArgumentException("Start time " + clock + " is not a time of day");
        }
        double seconds = hours * 3600 + minutes * 60;
        if (seconds < WARM_UP_SECONDS) {
            throw new IllegalArgumentException("Start time " + clock + " leaves no room for the "
                    + (int) (WARM_UP_SECONDS / 60) + "-minute warm-up after midnight");
        }
        return seconds;
    }

    private static List<TimeOfDayReport.DayRanking> rank(List<HeadwayStrategy> strategies,
                                                          List<TimeOfDayReport.Period> periods,
                                                          BenchmarkReport.RankingMetric metric) {
        List<TimeOfDayReport.DayRanking> unranked = new ArrayList<>();
        for (HeadwayStrategy strategy : strategies) {
            double wait = 0;
            double travel = 0;
            String worstClock = null;
            int worstRank = 0;
            for (TimeOfDayReport.Period period : periods) {
                BenchmarkReport.StrategyRanking r = period.report().rankings().stream()
                        .filter(x -> x.strategy().equals(strategy.name())).findFirst().orElseThrow();
                wait += r.meanWaitSeconds();
                travel += r.meanTravelMinutes();
                if (r.rank() > worstRank) {
                    worstRank = r.rank();
                    worstClock = period.startClock();
                }
            }
            unranked.add(new TimeOfDayReport.DayRanking(0, strategy.name(), wait / periods.size(),
                    travel / periods.size(), worstClock, worstRank));
        }
        Comparator<TimeOfDayReport.DayRanking> byMetric = metric == BenchmarkReport.RankingMetric.TRAVEL_TIME
                ? Comparator.comparingDouble(TimeOfDayReport.DayRanking::meanTravelMinutes)
                : Comparator.comparingDouble(TimeOfDayReport.DayRanking::meanWaitSeconds);
        unranked.sort(byMetric.thenComparing(TimeOfDayReport.DayRanking::strategy));
        List<TimeOfDayReport.DayRanking> ranked = new ArrayList<>();
        for (int i = 0; i < unranked.size(); i++) {
            TimeOfDayReport.DayRanking r = unranked.get(i);
            ranked.add(new TimeOfDayReport.DayRanking(i + 1, r.strategy(), r.meanWaitSeconds(), r.meanTravelMinutes(),
                    r.worstStartClock(), r.worstRank()));
        }
        return ranked;
    }
}
