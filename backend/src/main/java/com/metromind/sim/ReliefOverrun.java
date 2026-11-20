package com.metromind.sim;

/**
 * Late relief drivers: a share of crew changes take longer than planned because the relieving driver is late to
 * the platform, a handover runs on or a cab check finds something. Each crew change overruns with probability
 * {@code share}; an overrun lasts an exponentially distributed time with mean {@code meanSeconds}, capped at
 * {@link #CAP_MEANS} times the mean so no draw holds a train absurdly long.
 *
 * <p>Like {@link DwellVariability} the draw is a pure function of the seed, the train and the call it is made at,
 * so copies of an engine share it and what-if comparisons see the same late reliefs unless the incident changes
 * where trains call. The draws come from their own stream, independent of run-time and dwell draws from the same
 * seed.
 *
 * @param share       probability that a crew change overruns, 0 to 1; 0 for crew changes that always take exactly
 *                    their planned time
 * @param meanSeconds mean length of an overrun, seconds
 * @param seed        seed of the draws
 */
public record ReliefOverrun(double share, double meanSeconds, long seed) {

    /** Every crew change takes exactly its planned time. */
    public static final ReliefOverrun NONE = new ReliefOverrun(0, 0, 0);

    /** Longest mean overrun accepted: beyond this a late relief is a disruption, not everyday irregularity. */
    public static final double MAX_MEAN_SECONDS = 600;

    /** Overruns are capped at this many times their mean. */
    public static final double CAP_MEANS = 4;

    /** Separates the relief stream from the run-time and dwell streams drawn from the same seed. */
    private static final long STREAM = 0x5EA1C0DE7A7EB1D5L;

    public ReliefOverrun {
        if (!(share >= 0 && share <= 1)) {
            throw new IllegalArgumentException("Relief overrun share must be between 0 and 1");
        }
        if (!(meanSeconds >= 0 && meanSeconds <= MAX_MEAN_SECONDS)) {
            throw new IllegalArgumentException("Relief overrun mean must be between 0 and " + MAX_MEAN_SECONDS + " s");
        }
    }

    /** The same share and mean drawn from another seed, e.g. a benchmark's. */
    public ReliefOverrun withSeed(long seed) {
        return seed == this.seed ? this : new ReliefOverrun(share, meanSeconds, seed);
    }

    public boolean enabled() {
        return share > 0 && meanSeconds > 0;
    }

    /**
     * Extra seconds the crew change at call number {@code callIndex} of train {@code trainId} takes beyond its
     * planned time; 0 when this relief is on time.
     */
    public double extraSeconds(String trainId, long callIndex) {
        if (!enabled()) {
            return 0;
        }
        long s = seed ^ STREAM;
        if (SeededNoise.uniform(s, trainId, callIndex, 0) >= share) {
            return 0;
        }
        double u = SeededNoise.uniform(s, trainId, callIndex, 1);
        return Math.min(CAP_MEANS * meanSeconds, -meanSeconds * Math.log1p(-u));
    }
}
