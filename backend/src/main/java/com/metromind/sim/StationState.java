package com.metromind.sim;

import java.util.LinkedHashMap;
import java.util.Map;

final class StationState {
    final String id;
    /** Passengers waiting per platform queue (line + direction of travel), broken down by destination index. */
    final Map<QueueKey, double[]> waiting = new LinkedHashMap<>();
    /**
     * For each queue, the fraction of the station's new passenger arrivals that joins it, per destination.
     * Summed over queues and destinations this is 1 (or 0 if the station generates no trips).
     */
    final Map<QueueKey, double[]> arrivalShare = new LinkedHashMap<>();
    /**
     * Exponentially decayed count of passengers who joined each queue by changing lines here; divided by the
     * decay time constant it estimates the current transfer inflow rate.
     */
    final Map<QueueKey, Double> recentTransfers = new LinkedHashMap<>();
    /**
     * Passengers kept outside the platforms because they were full (see {@code Station#platformCapacity}). Their
     * destinations follow {@link #arrivalShare}, so a single count is enough.
     */
    double heldAtEntrance;
    /** Integral of waiting passengers over time, on the platforms or at the entrance (passenger-seconds). */
    double waitSeconds;
    /** Boardings, including passengers boarding their second or later line. */
    double served;
    /** Passengers who completed their journey here. */
    double delivered;
    /** Passengers who changed lines here. */
    double transfers;
    /**
     * Passengers who were on the platform when a train left in their direction but could not board because it was
     * full. Someone left behind by two trains in a row counts twice, as in operators' "denied boardings".
     */
    double leftBehind;
    double peakWaiting;

    StationState(String id) {
        this.id = id;
    }

    double totalWaiting() {
        double total = 0;
        for (double[] q : waiting.values()) {
            total += sum(q);
        }
        return total;
    }

    double waiting(QueueKey key) {
        double[] q = waiting.get(key);
        return q == null ? 0 : sum(q);
    }

    /** Fraction of the station's new arrivals that joins {@code key}, over all destinations. */
    double arrivalShare(QueueKey key) {
        double[] a = arrivalShare.get(key);
        return a == null ? 0 : sum(a);
    }

    static double sum(double[] values) {
        double total = 0;
        for (double v : values) {
            total += v;
        }
        return total;
    }

    StationState copy() {
        StationState s = new StationState(id);
        waiting.forEach((k, v) -> s.waiting.put(k, v.clone()));
        s.arrivalShare.putAll(arrivalShare); // immutable after construction
        s.recentTransfers.putAll(recentTransfers);
        s.waitSeconds = waitSeconds;
        s.served = served;
        s.delivered = delivered;
        s.transfers = transfers;
        s.leftBehind = leftBehind;
        s.heldAtEntrance = heldAtEntrance;
        s.peakWaiting = peakWaiting;
        return s;
    }

    /**
     * Identifies one platform queue: passengers for {@code lineId} travelling in {@code direction}
     * (+1 towards the line's last station, -1 towards its first station).
     */
    record QueueKey(String lineId, int direction) {
    }
}
