package com.metromind.network;

/**
 * One period of a line's time-of-day fleet plan: from {@code from} (time of day, {@code HH:mm}) until the next
 * period starts, the line runs {@code trains} trains. A plan repeats every day, so the last period carries on past
 * midnight until the first one starts.
 *
 * @param from   start of the period, {@code HH:mm} (00:00 to 23:59)
 * @param trains trains in service during the period, at least 1
 */
public record FleetPlanPeriod(String from, int trains) {

    public FleetPlanPeriod {
        if (from == null || !from.matches("\\d{2}:\\d{2}")) {
            throw new IllegalArgumentException("Fleet plan period start must be HH:mm, got " + from);
        }
        int hours = Integer.parseInt(from.substring(0, 2));
        int minutes = Integer.parseInt(from.substring(3));
        if (hours > 23 || minutes > 59) {
            throw new IllegalArgumentException("Fleet plan period start " + from + " is not a time of day");
        }
        if (trains < 1) {
            throw new IllegalArgumentException("Fleet plan period from " + from + " needs at least one train");
        }
    }

    /** Start of the period in seconds since midnight. */
    public int fromSeconds() {
        return Integer.parseInt(from.substring(0, 2)) * 3600 + Integer.parseInt(from.substring(3)) * 60;
    }
}
