package com.metromind.kpi;

/**
 * Key performance indicators for one minute of simulated time, aggregated from the telemetry stream.
 *
 * @param minute              minute of simulated time, {@code floor(simTime / 60)}
 * @param clock               start of the minute as {@code HH:mm} on the simulated clock
 * @param trainsReporting     distinct trains that sent a position during the minute
 * @param averageDelaySeconds mean lateness over all position samples (trains report every tick)
 * @param maxDelaySeconds     worst lateness seen
 * @param averageLoadFactor   mean of load / capacity over all position samples
 * @param averageWaiting      mean number of passengers waiting on platforms, network-wide
 * @param maxWaiting          peak number of passengers waiting on platforms, network-wide
 * @param averageHeadwayCv    mean headway coefficient of variation (0 = perfectly regular)
 * @param delayAlerts         trains that crossed the delay alert threshold during the minute
 * @param incidents           incidents that started during the minute
 */
public record MinuteKpi(long minute, String clock, int trainsReporting, double averageDelaySeconds,
                        double maxDelaySeconds, double averageLoadFactor, double averageWaiting, double maxWaiting,
                        double averageHeadwayCv, int delayAlerts, int incidents) {

    static String clockOf(long minute) {
        long m = Math.floorMod(minute, 24 * 60);
        return "%02d:%02d".formatted(m / 60, m % 60);
    }
}
