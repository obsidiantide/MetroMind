package com.metromind.sim;

/**
 * Everyday irregularity of dwell times: a door reopened for a late runner, a pram, a slow boarding. Each stop's
 * dwell, as worked out from the passengers alighting and boarding, is multiplied by a log-normal factor with
 * median 1 and log-standard deviation {@code sigma}, clamped to three standard deviations; the result never drops
 * below the minimum dwell, so a lucky draw cannot close the doors early.
 *
 * <p>Like {@link RunTimeVariability} the draw is a pure function of the seed, the train and how many stops it has
 * called at, so copies of an engine share it and what-if comparisons see the same dwells unless the incident
 * changes them. The draws come from their own stream: the same seed gives run times and dwells that are
 * independent of each other.
 *
 * @param sigma standard deviation of the log of the factor (0.1 ≈ ±10 %); 0 for dwells set by passengers alone
 * @param seed  seed of the draws
 */
public record DwellVariability(double sigma, long seed) {

    /** Every dwell is set by passengers alone. */
    public static final DwellVariability NONE = new DwellVariability(0, 0);

    /** Largest sigma accepted, as for run times. */
    public static final double MAX_SIGMA = 0.25;

    /** Separates the dwell stream from the run-time stream drawn from the same seed. */
    private static final long STREAM = 0xD3E11A5B7C0FFEE1L;

    public DwellVariability {
        if (!(sigma >= 0 && sigma <= MAX_SIGMA)) {
            throw new IllegalArgumentException("Dwell variability must be between 0 and " + MAX_SIGMA);
        }
    }

    public boolean enabled() {
        return sigma > 0;
    }

    /** The same seed with another spread, e.g. a line's own (see {@link com.metromind.network.Line#dwellVariability()}). */
    public DwellVariability withSigma(double sigma) {
        return sigma == this.sigma ? this : new DwellVariability(sigma, seed);
    }

    /** Factor applied to the dwell of call number {@code callIndex} of train {@code trainId}. */
    public double factor(String trainId, long callIndex) {
        return SeededNoise.logNormalFactor(sigma, seed ^ STREAM, trainId, callIndex);
    }
}
