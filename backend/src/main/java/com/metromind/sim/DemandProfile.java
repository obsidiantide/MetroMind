package com.metromind.sim;

/**
 * Time-of-day multiplier applied to each station's base arrival rate.
 * Two Gaussian peaks (08:15 and 18:00) on top of an off-peak base.
 */
public final class DemandProfile {
    private DemandProfile() {
    }

    public static double multiplier(double simSeconds) {
        double h = (simSeconds / 3600.0) % 24.0;
        if (h < 5.5) {
            return 0.15;
        }
        double morning = 2.6 * Math.exp(-Math.pow(h - 8.25, 2) / (2 * 0.8 * 0.8));
        double evening = 2.2 * Math.exp(-Math.pow(h - 18.0, 2) / (2 * 1.0 * 1.0));
        return 0.8 + morning + evening;
    }
}
