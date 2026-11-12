package com.metromind.sim;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param speed      simulated seconds per real second
 * @param tickMillis real milliseconds between simulation ticks / broadcasts
 * @param startClock simulated time of day at startup, HH:mm
 * @param strategy   initial headway strategy
 */
@ConfigurationProperties("metromind.sim")
public record SimulationProperties(double speed, long tickMillis, String startClock, String strategy) {
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
}
