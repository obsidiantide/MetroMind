package com.metromind.sim;

/**
 * Seeded, reproducible noise shared by the variability models: a log-normal factor with median 1 that is a pure
 * function of a seed, a train and an event counter, so every copy of an engine draws the same value for the same
 * train and event (common random numbers in what-if comparisons).
 */
final class SeededNoise {

    /** Draws are clamped to this many standard deviations, so no factor is absurdly small or large. */
    static final double CLAMP_SIGMAS = 3;

    private SeededNoise() {
    }

    /**
     * Log-normal factor with median 1 and log-standard deviation {@code sigma} for event number {@code index} of
     * train {@code trainId}; exactly 1 when {@code sigma} is 0.
     */
    static double logNormalFactor(double sigma, long seed, String trainId, long index) {
        if (sigma <= 0) {
            return 1;
        }
        long key = mix(seed ^ mix(trainId.hashCode() * 0x9E3779B97F4A7C15L + index));
        // Box–Muller from two independent uniforms in (0, 1].
        double u1 = ((mix(key) >>> 11) + 1) * 0x1.0p-53;
        double u2 = (mix(key + 0x632BE59BD9B4E019L) >>> 11) * 0x1.0p-53;
        double z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
        return Math.exp(sigma * Math.max(-CLAMP_SIGMAS, Math.min(CLAMP_SIGMAS, z)));
    }

    /**
     * Uniform number in [0, 1) for event number {@code index} of train {@code trainId}; draw {@code draw} of the
     * same event gives an independent number, for models that need more than one per event.
     */
    static double uniform(long seed, String trainId, long index, int draw) {
        long key = mix(seed ^ mix(trainId.hashCode() * 0x9E3779B97F4A7C15L + index));
        return (mix(key + draw * 0x632BE59BD9B4E019L) >>> 11) * 0x1.0p-53;
    }

    /** SplitMix64 finaliser: a fixed, well-mixed 64-bit hash. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
