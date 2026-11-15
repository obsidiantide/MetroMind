package com.metromind.incident;

import com.metromind.sim.SimulationEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * How long a line takes to get its headways back after an incident.
 *
 * <p>The incident run and its baseline (same start state, no incident) are sampled at the same fixed interval, and
 * at each sample the headway coefficient of variation of the incident run is compared with the baseline's. A
 * sample is <em>disrupted</em> when the incident run is more than {@link #CV_TOLERANCE} above the baseline. The
 * network has recovered at the first sample after the last disrupted one: from then until the horizon its
 * headways are as even as they would have been without the incident.
 *
 * <p>Comparing against the baseline rather than against a fixed CV keeps bunching that would have happened anyway
 * (and strategies that never run perfectly even headways) from counting as incident damage.
 */
public final class HeadwayRecovery {

    /** Headway CV above the baseline by more than this is still disrupted. */
    public static final double CV_TOLERANCE = 0.05;

    private HeadwayRecovery() {
    }

    /**
     * @param recoverySeconds seconds from the incident until headways were back within tolerance of the baseline
     *                        for good; 0 if they never left it, {@code null} if still disrupted at the horizon
     * @param peakCvExcess    largest amount by which the incident run's headway CV exceeded the baseline's
     */
    public record Result(Double recoverySeconds, double peakCvExcess) {

        public boolean recovered() {
            return recoverySeconds != null;
        }
    }

    /**
     * @param baselineCv    headway CV of the run without the incident, sample {@code i} taken at
     *                      {@code i * sampleSeconds} after the incident (sample 0 is the moment it is injected)
     * @param incidentCv    headway CV of the incident run at the same instants
     * @param sampleSeconds interval between samples
     */
    public static Result measure(List<Double> baselineCv, List<Double> incidentCv, double sampleSeconds) {
        if (baselineCv.size() != incidentCv.size()) {
            throw new IllegalArgumentException("Both runs must be sampled at the same instants");
        }
        if (sampleSeconds <= 0) {
            throw new IllegalArgumentException("Sample interval must be positive");
        }
        int lastDisrupted = -1;
        double peak = 0;
        for (int i = 0; i < incidentCv.size(); i++) {
            double excess = incidentCv.get(i) - baselineCv.get(i);
            peak = Math.max(peak, excess);
            if (excess > CV_TOLERANCE) {
                lastDisrupted = i;
            }
        }
        if (lastDisrupted == incidentCv.size() - 1) {
            return new Result(null, peak);
        }
        return new Result((lastDisrupted + 1) * sampleSeconds, peak);
    }

    /**
     * Runs {@code engine} for {@code horizon} seconds and returns its headway CV now and after every
     * {@code sampleSeconds}, ready for {@link #measure}. Running in chunks steps the engine exactly as one long run
     * would, so sampling does not change the simulation.
     */
    public static List<Double> sampleHeadwayCv(SimulationEngine engine, double horizon, double sampleSeconds,
                                               double dt) {
        List<Double> samples = new ArrayList<>(List.of(engine.headwayCv()));
        double elapsed = 0;
        while (elapsed < horizon - 1e-9) {
            double chunk = Math.min(sampleSeconds, horizon - elapsed);
            engine.run(chunk, dt);
            elapsed += chunk;
            samples.add(engine.headwayCv());
        }
        return samples;
    }
}
