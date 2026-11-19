package com.metromind.incident;

import java.util.List;

/**
 * Strategy rankings over several times of day: the same benchmark run from a network warmed up to each start time,
 * so strategies are compared over thin and full periods of the fleet plan and demand, not only the current moment.
 *
 * @param seed       seed every period's scenarios and run-time and dwell draws come from
 * @param metric     what the rankings are ordered by
 * @param periods    the benchmark of each start time, in the order requested
 * @param rankings   one entry per strategy, best first over the whole day
 */
public record TimeOfDayReport(long seed, double horizonSeconds, BenchmarkReport.RankingMetric metric,
                              List<Period> periods, List<DayRanking> rankings) {

    public TimeOfDayReport {
        periods = List.copyOf(periods);
        rankings = List.copyOf(rankings);
    }

    /** The benchmark run from one time of day ({@code HH:mm}). */
    public record Period(String startClock, BenchmarkReport report) {
    }

    /**
     * A strategy's standing over all the periods. Means are the averages of its per-period means, so every time of
     * day weighs the same however many passengers travel then.
     *
     * @param worstStartClock the period where the strategy ranked lowest (the earliest on a tie)
     * @param worstRank       its rank there
     */
    public record DayRanking(int rank, String strategy, double meanWaitSeconds, double meanTravelMinutes,
                             String worstStartClock, int worstRank) {
    }
}
