package com.metromind.sim;

/**
 * Everyday irregularity of running times: each run between two stations takes its nominal time multiplied by a
 * factor drawn from a log-normal distribution with median 1 and log-standard deviation {@code sigma}, clamped to
 * three standard deviations so no run is absurdly short or long.
 *
 * <p>The draw is a pure function of the seed, the train and how many runs it has started, so it is reproducible
 * and the same in every copy of an engine: in a what-if comparison a train's n-th run takes the same time in the
 * baseline and the incident run unless the incident itself changes it (common random numbers), and differences
 * come from the incident, not from noise.
 *
 * @param sigma standard deviation of the log of the factor (0.05 ≈ ±5 %); 0 for nominal run times
 * @param seed  seed of the draws
 */
public record RunTimeVariability(double sigma, long seed) {

    /** Every run takes exactly its nominal time. */
    public static final RunTimeVariability NONE = new RunTimeVariability(0, 0);

    /** Largest sigma accepted: beyond that a run could take more than twice or less than half its nominal time. */
    public static final double MAX_SIGMA = 0.25;

    public RunTimeVariability {
        if (!(sigma >= 0 && sigma <= MAX_SIGMA)) {
            throw new IllegalArgumentException("Run-time variability must be between 0 and " + MAX_SIGMA);
        }
    }

    public boolean enabled() {
        return sigma > 0;
    }

    /** Factor applied to the nominal time of run number {@code runIndex} of train {@code trainId}. */
    public double factor(String trainId, long runIndex) {
        if (!enabled()) {
            return 1;
        }
        long key = mix(seed ^ mix(trainId.hashCode() * 0x9E3779B97F4A7C15L + runIndex));
        // Box–Muller from two independent uniforms in (0, 1].
        double u1 = ((mix(key) >>> 11) + 1) * 0x1.0p-53;
        double u2 = (mix(key + 0x632BE59BD9B4E019L) >>> 11) * 0x1.0p-53;
        double z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
        return Math.exp(sigma * Math.max(-3, Math.min(3, z)));
    }

    /** SplitMix64 finaliser: a fixed, well-mixed 64-bit hash. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
