package com.metromind.sim;

import java.util.ArrayList;
import java.util.List;

/** Mutable state of one train. Package-private mutation; exposed to the outside via {@link SimulationSnapshot}. */
public final class TrainState {
    final String id;
    final String lineId;
    /** Service pattern the train follows; {@link #stop} indexes that pattern's loop. */
    final String patternId;
    final int capacity;

    int stop;
    TrainPhase phase = TrainPhase.DWELLING;
    double phaseRemaining;
    double segmentElapsed;
    double segmentDuration;
    /** Passengers on board, total; always equal to the sum of {@link #onboard}. */
    double passengers;
    /** Passengers on board by destination station index. */
    final double[] onboard;
    /** Time the train was scheduled to arrive at {@link #stop}. */
    double scheduledArrival;
    double delaySeconds;
    /** Freezes the train regardless of phase (injected delay or technical failure). */
    double blockedRemaining;
    /** Simulated time at which the current stoppage ({@link #blockedRemaining} &gt; 0) began. */
    double blockedSince;
    boolean failed;
    boolean holdDecided;
    boolean delayAlertActive;
    /** Whether the train ran through its last stop without calling (skip-stop); it never skips two in a row. */
    boolean skippedLastStop;
    final List<Double> arrivals = new ArrayList<>();

    TrainState(String id, String lineId, String patternId, int capacity, int stationCount) {
        this.id = id;
        this.lineId = lineId;
        this.patternId = patternId;
        this.capacity = capacity;
        this.onboard = new double[stationCount];
    }

    TrainState copy() {
        TrainState t = new TrainState(id, lineId, patternId, capacity, onboard.length);
        System.arraycopy(onboard, 0, t.onboard, 0, onboard.length);
        t.stop = stop;
        t.phase = phase;
        t.phaseRemaining = phaseRemaining;
        t.segmentElapsed = segmentElapsed;
        t.segmentDuration = segmentDuration;
        t.passengers = passengers;
        t.scheduledArrival = scheduledArrival;
        t.delaySeconds = delaySeconds;
        t.blockedRemaining = blockedRemaining;
        t.blockedSince = blockedSince;
        t.failed = failed;
        t.holdDecided = holdDecided;
        t.delayAlertActive = delayAlertActive;
        t.skippedLastStop = skippedLastStop;
        t.arrivals.addAll(arrivals);
        return t;
    }

    public String id() { return id; }
    public String lineId() { return lineId; }
    public String patternId() { return patternId; }
    public int stop() { return stop; }
    public TrainPhase phase() { return phase; }
    public double passengers() { return passengers; }
    public int capacity() { return capacity; }
    public double delaySeconds() { return delaySeconds; }
    public boolean failed() { return failed; }
    public List<Double> arrivals() { return List.copyOf(arrivals); }
}
