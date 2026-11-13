package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, discrete-time simulation of a metro network.
 *
 * <p>Passenger demand is modelled as a fluid (no randomness), so two copies of the engine fed the same inputs
 * produce identical results. This is what makes counterfactual analysis ("what if train R-3 is delayed by
 * 4 minutes?") possible: copy the engine, inject the incident into one copy and compare.
 *
 * <p>Each station keeps one passenger queue per line and direction of travel. Arriving passengers are split
 * evenly between the lines serving the station, then between the two directions in proportion to the number
 * of stations ahead in each direction (so a terminal only has a queue towards the other end of the line).
 * A train boards only the queue for the direction it is about to travel in.
 *
 * <p>Signalling uses fixed blocks: a train may only leave a platform when the next segment and the next
 * platform are both free. Delays therefore propagate to followers naturally.
 *
 * <p>Not thread-safe; callers synchronise (see {@code SimulationService}).
 */
public final class SimulationEngine {

    public static final double NOMINAL_DWELL = 25;
    static final double MIN_DWELL = 15;
    static final double MAX_DWELL = 90;
    static final double SECONDS_PER_PASSENGER = 0.04;
    static final double ALIGHT_FRACTION = 1.0 / 6.0;
    static final double DELAY_ALERT_SECONDS = 120;
    static final double DELAY_RECOVERED_SECONDS = 60;
    static final double OVERLOAD_HIGH = 400;
    static final double OVERLOAD_MEDIUM = 200;
    private static final int MAX_EVENTS = 100;

    private final MetroNetwork network;
    private final Map<String, Line> lines = new LinkedHashMap<>();
    private final Map<String, double[]> cumulativeStopSeconds = new LinkedHashMap<>();
    private final Map<String, List<String>> linesByStation = new LinkedHashMap<>();

    private final List<TrainState> trains = new ArrayList<>();
    private final Map<String, StationState> stations = new LinkedHashMap<>();
    private final Deque<SimEvent> events = new ArrayDeque<>();
    private final Map<String, String> overloadState = new LinkedHashMap<>();
    private HeadwayStrategy strategy;
    private double simTime;
    private boolean recordArrivals;

    public SimulationEngine(MetroNetwork network, double startTime, HeadwayStrategy strategy) {
        this.network = network;
        this.simTime = startTime;
        this.strategy = strategy;
        for (Line line : network.lines()) {
            lines.put(line.id(), line);
            double[] cum = new double[line.loopLength() + 1];
            for (int k = 0; k < line.loopLength(); k++) {
                cum[k + 1] = cum[k] + line.runSecondsFromStop(k) + NOMINAL_DWELL;
            }
            cumulativeStopSeconds.put(line.id(), cum);
            for (String sid : line.stationIds()) {
                linesByStation.computeIfAbsent(sid, k -> new ArrayList<>()).add(line.id());
            }
        }
        for (Station s : network.stations()) {
            StationState st = new StationState(s.id());
            List<String> stationLines = linesByStation.getOrDefault(s.id(), List.of());
            for (String lid : stationLines) {
                Line line = lines.get(lid);
                double outbound = outboundShare(line, line.stationIds().indexOf(s.id()));
                addQueue(st, new StationState.QueueKey(lid, 1), outbound / stationLines.size());
                addQueue(st, new StationState.QueueKey(lid, -1), (1 - outbound) / stationLines.size());
            }
            stations.put(s.id(), st);
        }
        placeTrains();
    }

    private static void addQueue(StationState st, StationState.QueueKey key, double share) {
        if (share > 0) {
            st.waiting.put(key, 0.0);
            st.arrivalShare.put(key, share);
        }
    }

    /**
     * Fraction of passengers at the station with index {@code index} on {@code line} who travel outbound
     * (towards the last station). Destinations are assumed to be spread evenly over the other stations of the
     * line, so the split follows the number of stations ahead in each direction.
     */
    static double outboundShare(Line line, int index) {
        int last = line.stationIds().size() - 1;
        if (index < 0 || index > last) {
            throw new IllegalArgumentException("Station index " + index + " is not on line " + line.id());
        }
        return (double) (last - index) / last;
    }

    private SimulationEngine(SimulationEngine other) {
        this.network = other.network;
        this.lines.putAll(other.lines);
        this.cumulativeStopSeconds.putAll(other.cumulativeStopSeconds);
        this.linesByStation.putAll(other.linesByStation);
        other.trains.forEach(t -> this.trains.add(t.copy()));
        other.stations.forEach((k, v) -> this.stations.put(k, v.copy()));
        this.events.addAll(other.events);
        this.overloadState.putAll(other.overloadState);
        this.strategy = other.strategy;
        this.simTime = other.simTime;
        this.recordArrivals = other.recordArrivals;
    }

    /** Deep copy, used for counterfactual ("what-if") runs. */
    public SimulationEngine copy() {
        return new SimulationEngine(this);
    }

    private void placeTrains() {
        for (Line line : network.lines()) {
            double[] cum = cumulativeStopSeconds.get(line.id());
            double loop = cum[line.loopLength()];
            int used = -1;
            for (int i = 0; i < line.trainCount(); i++) {
                double target = i * loop / line.trainCount();
                int stop = 0;
                while (stop + 1 < line.loopLength() && cum[stop + 1] <= target) {
                    stop++;
                }
                if (stop <= used) {
                    stop = used + 1;
                }
                if (stop >= line.loopLength()) {
                    throw new IllegalStateException("Too many trains for line " + line.id());
                }
                used = stop;
                TrainState t = new TrainState(line.id() + "-" + (i + 1), line.id(), line.trainCapacity());
                t.stop = stop;
                t.phase = TrainPhase.DWELLING;
                t.phaseRemaining = NOMINAL_DWELL;
                t.scheduledArrival = simTime;
                trains.add(t);
            }
        }
    }

    // ------------------------------------------------------------------ stepping

    public void run(double seconds, double dt) {
        double end = simTime + seconds;
        while (simTime < end - 1e-9) {
            step(Math.min(dt, end - simTime));
        }
    }

    public void step(double dt) {
        simTime += dt;
        accumulateDemand(dt);
        for (TrainState t : trains) {
            advance(t, dt);
        }
    }

    private void accumulateDemand(double dt) {
        double mult = DemandProfile.multiplier(simTime);
        for (Station s : network.stations()) {
            StationState st = stations.get(s.id());
            if (st.waiting.isEmpty()) {
                continue;
            }
            double arrivals = s.baseArrivalsPerMinute() * mult * dt / 60.0;
            st.waiting.replaceAll((key, w) -> w + arrivals * st.arrivalShare.get(key));
            double total = st.totalWaiting();
            st.waitSeconds += total * dt;
            st.peakWaiting = Math.max(st.peakWaiting, total);
        }
    }

    private void advance(TrainState t, double dt) {
        if (t.blockedRemaining > 0) {
            t.blockedRemaining -= dt;
            if (t.blockedRemaining <= 0 && t.failed) {
                t.failed = false;
                emit(SimEvent.Type.INCIDENT_CLEARED, t.id, "Train " + t.id + " is back in service");
            }
            return;
        }
        switch (t.phase) {
            case DWELLING -> {
                t.phaseRemaining -= dt;
                if (t.phaseRemaining <= 0) {
                    decideHoldOrDepart(t);
                }
            }
            case HELD -> {
                t.phaseRemaining -= dt;
                if (t.phaseRemaining <= 0) {
                    tryDepart(t);
                }
            }
            case WAITING_SIGNAL -> tryDepart(t);
            case RUNNING -> {
                t.segmentElapsed += dt;
                if (t.segmentElapsed >= t.segmentDuration) {
                    arrive(t);
                }
            }
        }
    }

    private void decideHoldOrDepart(TrainState t) {
        if (!t.holdDecided) {
            t.holdDecided = true;
            Line line = lines.get(t.lineId);
            double target = cumulativeStopSeconds.get(line.id())[line.loopLength()] / line.trainCount();
            double hold = strategy.holdSeconds(forwardHeadway(t), backwardHeadway(t), target);
            if (hold > 0.5) {
                t.phase = TrainPhase.HELD;
                t.phaseRemaining = hold;
                return;
            }
        }
        tryDepart(t);
    }

    private void tryDepart(TrainState t) {
        Line line = lines.get(t.lineId);
        int next = (t.stop + 1) % line.loopLength();
        for (TrainState o : trains) {
            if (o == t || !o.lineId.equals(t.lineId)) {
                continue;
            }
            boolean inSegment = o.phase == TrainPhase.RUNNING && o.stop == t.stop;
            boolean atNextPlatform = o.phase != TrainPhase.RUNNING && o.stop == next;
            if (inSegment || atNextPlatform) {
                t.phase = TrainPhase.WAITING_SIGNAL;
                return;
            }
        }
        t.phase = TrainPhase.RUNNING;
        t.segmentElapsed = 0;
        t.segmentDuration = line.runSecondsFromStop(t.stop);
    }

    private void arrive(TrainState t) {
        Line line = lines.get(t.lineId);
        t.scheduledArrival += line.runSecondsFromStop(t.stop) + NOMINAL_DWELL;
        t.stop = (t.stop + 1) % line.loopLength();
        t.delaySeconds = Math.max(0, simTime - t.scheduledArrival);
        if (recordArrivals) {
            t.arrivals.add(simTime);
        }
        detectDelay(t);

        String stationId = line.stationAtStop(t.stop);
        StationState st = stations.get(stationId);
        boolean terminal = t.stop == 0 || t.stop == line.stationIds().size() - 1;
        double alight = terminal ? t.passengers : t.passengers * ALIGHT_FRACTION;
        t.passengers -= alight;
        StationState.QueueKey queue = new StationState.QueueKey(t.lineId, line.directionAtStop(t.stop));
        double waiting = st.waiting.getOrDefault(queue, 0.0);
        double board = Math.min(waiting, t.capacity - t.passengers);
        st.waiting.put(queue, waiting - board);
        st.served += board;
        t.passengers += board;

        t.phase = TrainPhase.DWELLING;
        t.holdDecided = false;
        t.phaseRemaining = Math.max(MIN_DWELL,
                Math.min(MAX_DWELL, MIN_DWELL + SECONDS_PER_PASSENGER * (alight + board)));
    }

    private void detectDelay(TrainState t) {
        if (!t.delayAlertActive && t.delaySeconds >= DELAY_ALERT_SECONDS) {
            t.delayAlertActive = true;
            emit(SimEvent.Type.DELAY_DETECTED, t.id,
                    "Train %s is %.0f s behind schedule".formatted(t.id, t.delaySeconds));
        } else if (t.delayAlertActive && t.delaySeconds <= DELAY_RECOVERED_SECONDS) {
            t.delayAlertActive = false;
            emit(SimEvent.Type.DELAY_RECOVERED, t.id, "Train " + t.id + " recovered its schedule");
        }
    }

    // ------------------------------------------------------------------ headways

    /** Position of a train along its line's loop, in nominal seconds since stop 0. */
    double loopPosition(TrainState t) {
        double[] cum = cumulativeStopSeconds.get(t.lineId);
        double base = cum[t.stop];
        if (t.phase == TrainPhase.RUNNING) {
            return base + NOMINAL_DWELL + t.segmentElapsed;
        }
        return base;
    }

    private double loopTotal(String lineId) {
        Line line = lines.get(lineId);
        return cumulativeStopSeconds.get(lineId)[line.loopLength()];
    }

    double forwardHeadway(TrainState t) {
        double total = loopTotal(t.lineId);
        double pos = loopPosition(t);
        double best = total;
        for (TrainState o : trains) {
            if (o != t && o.lineId.equals(t.lineId)) {
                double d = Math.floorMod((long) Math.round((loopPosition(o) - pos) * 1000), (long) (total * 1000)) / 1000.0;
                if (d > 0 && d < best) {
                    best = d;
                }
            }
        }
        return best;
    }

    double backwardHeadway(TrainState t) {
        double total = loopTotal(t.lineId);
        double pos = loopPosition(t);
        double best = total;
        for (TrainState o : trains) {
            if (o != t && o.lineId.equals(t.lineId)) {
                double d = Math.floorMod((long) Math.round((pos - loopPosition(o)) * 1000), (long) (total * 1000)) / 1000.0;
                if (d > 0 && d < best) {
                    best = d;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ incidents

    /** Holds a train in place for {@code seconds} (e.g. door fault, passenger incident). */
    public void injectDelay(String trainId, double seconds) {
        TrainState t = train(trainId);
        t.blockedRemaining += seconds;
        emit(SimEvent.Type.INCIDENT, trainId, "Train %s delayed by %.0f s".formatted(trainId, seconds));
    }

    /** Technical failure: the train stops where it is and blocks the track until repaired. */
    public void injectBreakdown(String trainId, double repairSeconds) {
        TrainState t = train(trainId);
        t.blockedRemaining += repairSeconds;
        t.failed = true;
        emit(SimEvent.Type.INCIDENT, trainId, "Train %s broke down, repair ETA %.0f s".formatted(trainId, repairSeconds));
    }

    // ------------------------------------------------------------------ prediction

    /**
     * Predicts how many passengers will be waiting at a station after {@code horizon} seconds. Each platform
     * queue (line + direction) is forecast separately: current queue + expected arrivals − residual capacity of
     * the trains that will call at the station in that direction within the horizon. A train travelling the
     * other way cannot relieve a queue, so a surplus in one direction never offsets a backlog in the other.
     */
    public double predictWaiting(String stationId, double horizon) {
        StationState st = stations.get(stationId);
        Station station = network.station(stationId);
        double inflow = station.baseArrivalsPerMinute() * DemandProfile.multiplier(simTime + horizon / 2) * horizon / 60.0;
        double predicted = 0;
        for (Map.Entry<StationState.QueueKey, Double> queue : st.waiting.entrySet()) {
            StationState.QueueKey key = queue.getKey();
            Line line = lines.get(key.lineId());
            // Fraction of this station's demand on this line that travels in this direction.
            double directionShare = key.direction() > 0
                    ? outboundShare(line, line.stationIds().indexOf(stationId))
                    : 1 - outboundShare(line, line.stationIds().indexOf(stationId));
            double stationShare = demandShare(line, station) * directionShare;
            double expected = queue.getValue() + inflow * st.arrivalShare.get(key);
            for (TrainState t : trains) {
                if (!t.lineId.equals(key.lineId())) {
                    continue;
                }
                double eta = secondsUntilStop(t, line, stationId, key.direction());
                if (eta <= horizon) {
                    // A train's spare room is shared by every station it still serves, roughly in proportion
                    // to demand, so this queue can only count on its share of it.
                    double residual = Math.max(0, t.capacity - t.passengers * (1 - ALIGHT_FRACTION));
                    expected -= Math.min(residual, residual * 2 * stationShare);
                }
            }
            predicted += Math.max(0, expected);
        }
        return predicted;
    }

    private double demandShare(Line line, Station station) {
        double total = line.stationIds().stream().map(network::station)
                .mapToDouble(Station::baseArrivalsPerMinute).sum();
        return total == 0 ? 0 : station.baseArrivalsPerMinute() / total;
    }

    /** Seconds until {@code t} calls at {@code stationId} ready to depart in {@code direction}. */
    private double secondsUntilStop(TrainState t, Line line, String stationId, int direction) {
        double[] cum = cumulativeStopSeconds.get(line.id());
        double total = cum[line.loopLength()];
        double pos = loopPosition(t);
        double best = Double.MAX_VALUE;
        for (int k = 0; k < line.loopLength(); k++) {
            if (line.stationAtStop(k).equals(stationId) && line.directionAtStop(k) == direction) {
                double d = cum[k] - pos;
                if (d <= 0) {
                    d += total;
                }
                best = Math.min(best, d);
            }
        }
        return best + Math.max(0, t.blockedRemaining);
    }

    private void updateOverloadAlerts() {
        for (String sid : stations.keySet()) {
            String risk = riskLevel(predictWaiting(sid, 900));
            String prev = overloadState.put(sid, risk);
            if ("HIGH".equals(risk) && !"HIGH".equals(prev)) {
                emit(SimEvent.Type.OVERLOAD_PREDICTED, sid,
                        "Station " + network.station(sid).name() + " is predicted to be overloaded within 15 min");
            }
        }
    }

    static String riskLevel(double predicted) {
        if (predicted >= OVERLOAD_HIGH) {
            return "HIGH";
        }
        return predicted >= OVERLOAD_MEDIUM ? "MEDIUM" : "LOW";
    }

    // ------------------------------------------------------------------ views

    public SimulationSnapshot snapshot() {
        updateOverloadAlerts();
        List<SimulationSnapshot.TrainView> trainViews = new ArrayList<>();
        for (TrainState t : trains) {
            Line line = lines.get(t.lineId);
            Station from = network.station(line.stationAtStop(t.stop));
            Station to = network.station(line.stationAtStop((t.stop + 1) % line.loopLength()));
            double progress = t.phase == TrainPhase.RUNNING ? Math.min(1, t.segmentElapsed / t.segmentDuration) : 0;
            trainViews.add(new SimulationSnapshot.TrainView(t.id, t.lineId, from.id(), to.id(), progress,
                    from.lat() + (to.lat() - from.lat()) * progress,
                    from.lon() + (to.lon() - from.lon()) * progress,
                    t.phase, t.delaySeconds, t.passengers, t.capacity, t.failed));
        }
        List<SimulationSnapshot.StationView> stationViews = new ArrayList<>();
        for (StationState st : stations.values()) {
            double predicted = predictWaiting(st.id, 900);
            List<SimulationSnapshot.QueueView> queues = new ArrayList<>();
            st.waiting.forEach((key, waiting) -> {
                List<String> ids = lines.get(key.lineId()).stationIds();
                String towards = key.direction() > 0 ? ids.get(ids.size() - 1) : ids.get(0);
                queues.add(new SimulationSnapshot.QueueView(key.lineId(), towards, waiting));
            });
            stationViews.add(new SimulationSnapshot.StationView(st.id, st.totalWaiting(), predicted,
                    riskLevel(predicted), queues));
        }
        return new SimulationSnapshot(simTime, clock(simTime), strategy.name(), trainViews, stationViews,
                List.copyOf(events), metrics());
    }

    private SimulationSnapshot.Metrics metrics() {
        double avg = trains.stream().mapToDouble(t -> t.delaySeconds).average().orElse(0);
        double max = trains.stream().mapToDouble(t -> t.delaySeconds).max().orElse(0);
        double waiting = stations.values().stream().mapToDouble(StationState::totalWaiting).sum();
        double served = stations.values().stream().mapToDouble(s -> s.served).sum();
        return new SimulationSnapshot.Metrics(avg, max, waiting, served, headwayCv());
    }

    /** Coefficient of variation of headways across all lines: 0 = perfectly even, higher = bunching. */
    double headwayCv() {
        List<Double> cvs = new ArrayList<>();
        for (Line line : lines.values()) {
            List<TrainState> lt = trains.stream().filter(t -> t.lineId.equals(line.id()))
                    .sorted(Comparator.comparingDouble(this::loopPosition)).toList();
            if (lt.size() < 2) {
                continue;
            }
            double[] h = lt.stream().mapToDouble(this::forwardHeadway).toArray();
            double mean = java.util.Arrays.stream(h).average().orElse(0);
            double var = java.util.Arrays.stream(h).map(x -> (x - mean) * (x - mean)).average().orElse(0);
            cvs.add(mean == 0 ? 0 : Math.sqrt(var) / mean);
        }
        return cvs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    static String clock(double simTime) {
        long s = (long) simTime % 86400;
        return "%02d:%02d:%02d".formatted(s / 3600, (s / 60) % 60, s % 60);
    }

    private void emit(SimEvent.Type type, String subject, String message) {
        events.addLast(new SimEvent(simTime, type, subject, message));
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
    }

    // ------------------------------------------------------------------ accessors

    public double simTime() { return simTime; }
    public HeadwayStrategy strategy() { return strategy; }
    public void setStrategy(HeadwayStrategy strategy) { this.strategy = strategy; }
    public MetroNetwork network() { return network; }
    public void setRecordArrivals(boolean record) { this.recordArrivals = record; }
    public List<TrainState> trains() { return List.copyOf(trains); }

    public TrainState train(String id) {
        return trains.stream().filter(t -> t.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown train " + id));
    }

    public double stationWaitSeconds(String stationId) { return stations.get(stationId).waitSeconds; }
    public double stationPeakWaiting(String stationId) { return stations.get(stationId).peakWaiting; }
    public double stationWaiting(String stationId) { return stations.get(stationId).totalWaiting(); }

    /**
     * Passengers waiting at a station for one line and direction (+1 towards the line's last station,
     * -1 towards its first). Returns 0 for a queue that does not exist, e.g. outbound at the last station.
     */
    public double stationWaiting(String stationId, String lineId, int direction) {
        return stations.get(stationId).waiting.getOrDefault(new StationState.QueueKey(lineId, direction), 0.0);
    }
}
