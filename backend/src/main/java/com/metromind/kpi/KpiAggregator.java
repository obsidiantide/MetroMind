package com.metromind.kpi;

import com.metromind.sim.SimEvent;
import com.metromind.sim.SimulationSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Folds the telemetry stream (train positions, network metrics and events) into {@link MinuteKpi per-minute KPIs}.
 * <p>
 * Messages for different trains travel on different Kafka partitions, so they can arrive slightly out of order. A
 * minute is therefore kept open until the newest simulated time seen (the watermark) is {@code graceSeconds} past its
 * end; samples for a minute that has already been closed are dropped and counted in {@link #lateSamples()}.
 * Every {@code on...} call returns the minutes it closed, oldest first. Aggregation depends only on the messages and
 * their order, never on the wall clock.
 */
public final class KpiAggregator {

    public static final double DEFAULT_GRACE_SECONDS = 30;

    private final double graceSeconds;
    private final TreeMap<Long, Bucket> open = new TreeMap<>();
    private double watermark = Double.NEGATIVE_INFINITY;
    private long lastClosedMinute = Long.MIN_VALUE;
    private long lateSamples;

    public KpiAggregator() {
        this(DEFAULT_GRACE_SECONDS);
    }

    public KpiAggregator(double graceSeconds) {
        if (graceSeconds < 0 || !Double.isFinite(graceSeconds)) {
            throw new IllegalArgumentException("graceSeconds must be >= 0");
        }
        this.graceSeconds = graceSeconds;
    }

    public synchronized List<MinuteKpi> onPosition(double simTime, SimulationSnapshot.TrainView train) {
        Bucket b = bucket(simTime);
        if (b != null) {
            b.trains.add(train.id());
            b.delaySum += train.delaySeconds();
            b.delayMax = Math.max(b.delayMax, train.delaySeconds());
            b.delaySamples++;
            if (train.capacity() > 0) {
                b.loadFactorSum += train.load() / train.capacity();
                b.loadSamples++;
            }
        }
        return advance(simTime);
    }

    public synchronized List<MinuteKpi> onMetrics(double simTime, SimulationSnapshot.Metrics metrics) {
        Bucket b = bucket(simTime);
        if (b != null) {
            b.waitingSum += metrics.totalWaiting();
            b.waitingMax = Math.max(b.waitingMax, metrics.totalWaiting());
            b.cvSum += metrics.headwayCoefficientOfVariation();
            b.metricSamples++;
        }
        return advance(simTime);
    }

    public synchronized List<MinuteKpi> onEvent(SimEvent event) {
        Bucket b = bucket(event.simTime());
        if (b != null) {
            if (event.type() == SimEvent.Type.DELAY_DETECTED) {
                b.delayAlerts++;
            } else if (event.type() == SimEvent.Type.INCIDENT) {
                b.incidents++;
            }
        }
        return advance(event.simTime());
    }

    /** Closes every open minute, e.g. on shutdown. */
    public synchronized List<MinuteKpi> flush() {
        List<MinuteKpi> closed = new ArrayList<>();
        while (!open.isEmpty()) {
            closed.add(close(open.pollFirstEntry()));
        }
        return closed;
    }

    /** Samples dropped because their minute had already been closed. */
    public synchronized long lateSamples() {
        return lateSamples;
    }

    private Bucket bucket(double simTime) {
        long minute = (long) Math.floor(simTime / 60);
        if (minute <= lastClosedMinute) {
            lateSamples++;
            return null;
        }
        return open.computeIfAbsent(minute, m -> new Bucket());
    }

    private List<MinuteKpi> advance(double simTime) {
        watermark = Math.max(watermark, simTime);
        List<MinuteKpi> closed = new ArrayList<>();
        while (!open.isEmpty() && (open.firstKey() + 1) * 60 + graceSeconds <= watermark) {
            closed.add(close(open.pollFirstEntry()));
        }
        return closed;
    }

    private MinuteKpi close(Map.Entry<Long, Bucket> e) {
        long minute = e.getKey();
        Bucket b = e.getValue();
        lastClosedMinute = Math.max(lastClosedMinute, minute);
        return new MinuteKpi(minute, MinuteKpi.clockOf(minute), b.trains.size(),
                mean(b.delaySum, b.delaySamples), b.delaySamples == 0 ? 0 : b.delayMax,
                mean(b.loadFactorSum, b.loadSamples), mean(b.waitingSum, b.metricSamples),
                b.metricSamples == 0 ? 0 : b.waitingMax, mean(b.cvSum, b.metricSamples), b.delayAlerts, b.incidents);
    }

    private static double mean(double sum, long n) {
        return n == 0 ? 0 : sum / n;
    }

    private static final class Bucket {
        final Set<String> trains = new TreeSet<>();
        double delaySum;
        double delayMax = Double.NEGATIVE_INFINITY;
        long delaySamples;
        double loadFactorSum;
        long loadSamples;
        double waitingSum;
        double waitingMax = Double.NEGATIVE_INFINITY;
        double cvSum;
        long metricSamples;
        int delayAlerts;
        int incidents;
    }
}
