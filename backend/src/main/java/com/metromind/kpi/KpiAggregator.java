package com.metromind.kpi;

import com.metromind.sim.SimEvent;
import com.metromind.sim.SimulationSnapshot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Folds the telemetry stream (train positions, network metrics and events) into {@link MinuteKpi per-minute KPIs}.
 * <p>
 * Messages for different trains travel on different Kafka partitions, so they can arrive slightly out of order, and
 * each topic is consumed by its own listener, so one topic can lag far behind another. The aggregator therefore keeps
 * a watermark (newest simulated time seen) per {@link Source}. A minute is closed only once every
 * {@linkplain Source#gating gating} source that has delivered anything is {@code graceSeconds} past its end; a backlog
 * on positions holds minutes open instead of letting the metrics topic close them early. Events are sparse, so they
 * never hold a minute open. Samples for a minute that has already been closed are dropped and counted in
 * {@link #lateSamples()}. To bound memory when a gating source stalls, at most {@code maxOpenMinutes} minutes are kept
 * open and the oldest is closed when the limit is exceeded.
 * <p>
 * Every {@code on...} call returns the minutes it closed, oldest first. Aggregation depends only on the messages and
 * their order, never on the wall clock.
 */
public final class KpiAggregator {

    public static final double DEFAULT_GRACE_SECONDS = 30;
    /** One simulated hour: far beyond any normal lag, small enough to keep a stalled topic from growing memory. */
    public static final int DEFAULT_MAX_OPEN_MINUTES = 60;

    /** A telemetry stream feeding the aggregator; each has its own watermark. */
    public enum Source {
        POSITIONS(true), METRICS(true), EVENTS(false);

        /** Whether the source reports every tick, so a minute must wait for it before closing. */
        public final boolean gating;

        Source(boolean gating) {
            this.gating = gating;
        }
    }

    private final double graceSeconds;
    private final int maxOpenMinutes;
    private final TreeMap<Long, Bucket> open = new TreeMap<>();
    private final EnumMap<Source, Double> watermarks = new EnumMap<>(Source.class);
    private long lastClosedMinute = Long.MIN_VALUE;
    private long lateSamples;

    public KpiAggregator() {
        this(DEFAULT_GRACE_SECONDS);
    }

    public KpiAggregator(double graceSeconds) {
        this(graceSeconds, DEFAULT_MAX_OPEN_MINUTES);
    }

    public KpiAggregator(double graceSeconds, int maxOpenMinutes) {
        if (graceSeconds < 0 || !Double.isFinite(graceSeconds)) {
            throw new IllegalArgumentException("graceSeconds must be >= 0");
        }
        if (maxOpenMinutes < 1) {
            throw new IllegalArgumentException("maxOpenMinutes must be >= 1");
        }
        this.graceSeconds = graceSeconds;
        this.maxOpenMinutes = maxOpenMinutes;
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
        return advance(Source.POSITIONS, simTime);
    }

    public synchronized List<MinuteKpi> onMetrics(double simTime, SimulationSnapshot.Metrics metrics) {
        Bucket b = bucket(simTime);
        if (b != null) {
            b.waitingSum += metrics.totalWaiting();
            b.waitingMax = Math.max(b.waitingMax, metrics.totalWaiting());
            b.cvSum += metrics.headwayCoefficientOfVariation();
            b.metricSamples++;
        }
        return advance(Source.METRICS, simTime);
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
        return advance(Source.EVENTS, event.simTime());
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

    /** The newest simulated time seen per source, for sources that have delivered at least one message. */
    public synchronized Map<Source, Double> watermarks() {
        return Map.copyOf(watermarks);
    }

    /** Number of minutes currently open. */
    public synchronized int openMinutes() {
        return open.size();
    }

    private List<MinuteKpi> advance(Source source, double simTime) {
        watermarks.merge(source, simTime, Math::max);
        double closeBefore = closingWatermark();
        List<MinuteKpi> closed = new ArrayList<>();
        while (!open.isEmpty()
                && ((open.firstKey() + 1) * 60 + graceSeconds <= closeBefore || open.size() > maxOpenMinutes)) {
            closed.add(close(open.pollFirstEntry()));
        }
        return closed;
    }

    /** The slowest gating source that has reported; the events watermark if no gating source has reported yet. */
    private double closingWatermark() {
        double slowest = Double.POSITIVE_INFINITY;
        boolean anyGating = false;
        for (Map.Entry<Source, Double> e : watermarks.entrySet()) {
            if (e.getKey().gating) {
                slowest = Math.min(slowest, e.getValue());
                anyGating = true;
            }
        }
        return anyGating ? slowest : watermarks.getOrDefault(Source.EVENTS, Double.NEGATIVE_INFINITY);
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
