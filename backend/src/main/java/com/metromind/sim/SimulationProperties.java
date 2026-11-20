package com.metromind.sim;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param speed              simulated seconds per real second
 * @param tickMillis         real milliseconds between simulation ticks / broadcasts
 * @param startClock         simulated time of day at startup, HH:mm
 * @param strategy           initial headway strategy
 * @param runTimeVariability spread of run times between stations around their nominal value (see
 *                           {@link RunTimeVariability#sigma()}); 0, the default, runs every train to the minute
 * @param runTimeSeed        seed of the live engine's run-time draws, and of its dwell draws (an independent stream)
 * @param dwellVariability   spread of dwells around the one passengers set (see {@link DwellVariability#sigma()});
 *                           0, the default, leaves dwells to boarding and alighting alone
 * @param reliefOverrunShare share of crew changes whose relief driver is late (see {@link ReliefOverrun#share()});
 *                           0, the default, runs every crew change to plan
 * @param reliefOverrunSeconds mean length of a late relief's overrun, seconds
 */
@ConfigurationProperties("metromind.sim")
public record SimulationProperties(double speed, long tickMillis, String startClock, String strategy,
                                   double runTimeVariability, long runTimeSeed, double dwellVariability,
                                   double reliefOverrunShare, double reliefOverrunSeconds) {
    public SimulationProperties {
        if (speed <= 0) speed = 10;
        if (tickMillis <= 0) tickMillis = 500;
        if (startClock == null) startClock = "07:30";
        if (strategy == null) strategy = "NO_CONTROL";
    }

    public double startSeconds() {
        String[] p = startClock.split(":");
        return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60;
    }

    /** The live engine's run-time variability; rejects a spread outside the supported range at startup. */
    public RunTimeVariability variability() {
        return new RunTimeVariability(runTimeVariability, runTimeSeed);
    }

    /** The live engine's dwell variability; rejects a spread outside the supported range at startup. */
    public DwellVariability dwells() {
        return new DwellVariability(dwellVariability, runTimeSeed);
    }

    /** The live engine's late reliefs, seeded like its dwells; rejects values outside the supported range at startup. */
    public ReliefOverrun reliefOverrun() {
        return new ReliefOverrun(reliefOverrunShare, reliefOverrunSeconds, runTimeSeed);
    }
}
