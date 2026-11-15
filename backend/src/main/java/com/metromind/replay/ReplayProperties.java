package com.metromind.replay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param retentionMinutes how much simulated time snapshots are kept for
 * @param intervalSeconds  simulated seconds between two kept snapshots
 */
@ConfigurationProperties("metromind.replay")
public record ReplayProperties(int retentionMinutes, double intervalSeconds) {
    public ReplayProperties {
        if (retentionMinutes <= 0) retentionMinutes = 30;
        if (intervalSeconds <= 0) intervalSeconds = 10;
    }

    public double retentionSeconds() {
        return retentionMinutes * 60.0;
    }
}
