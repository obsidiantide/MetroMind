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
 * <p>Passengers travel between an origin and a destination drawn from an {@link OdMatrix} and follow the
 * cheapest route computed by {@link RoutePlanner}, changing lines at interchanges where that is faster. Each
 * station keeps one passenger queue per line and direction of travel, broken down by destination. A train boards
 * only the queue for the direction it is about to travel in; passengers leave the train at their destination,
 * or at an interchange where they join the queue for their next line.
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
    /** Time constant of the moving average used to estimate transfer inflow for predictions. */
    static final double TRANSFER_RATE_WINDOW_SECONDS = 600;
    static final double DELAY_ALERT_SECONDS = 120;
    static final double DELAY_RECOVERED_SECONDS = 60;
    static final double OVERLOAD_HIGH = 400;
    static final double OVERLOAD_MEDIUM = 200;
    private static final int MAX_EVENTS = 100;

    private final MetroNetwork network;
    private final Map<String, Line> lines = new LinkedHashMap<>();
    private final Map<String, double[]> cumulativeStopSeconds = new LinkedHashMap<>();
    private final Map<String, List<String>> linesByStation = new LinkedHashMap<>();
    /** Stations next to each station on any line, in network order; where a closed station's passengers go. */
    private final Map<String, List<String>> neighbours = new LinkedHashMap<>();
    private final RoutePlanner routes;
    private final OdMatrix od;

    private final List<TrainState> trains = new ArrayList<>();
    private final Map<String, StationState> stations = new LinkedHashMap<>();
    private final Deque<SimEvent> events = new ArrayDeque<>();
    private final Map<String, String> overloadState = new LinkedHashMap<>();
    private final List<SectionClosure> closures = new ArrayList<>();
    /** Closed stations and the seconds until each reopens. */
    private final Map<String, Double> closedStations = new LinkedHashMap<>();
    private HeadwayStrategy strategy;
    private double simTime;
    private boolean recordArrivals;
    /** Integral of passengers on board over time, all trains (passenger-seconds). */
    private double rideSeconds;

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
            List<String> adjacent = new ArrayList<>();
            for (Station other : network.stations()) {
                boolean next = network.lines().stream().anyMatch(l -> {
                    int a = l.stationIds().indexOf(s.id());
                    int b = l.stationIds().indexOf(other.id());
                    return a >= 0 && b >= 0 && Math.abs(a - b) == 1;
                });
                if (next) {
                    adjacent.add(other.id());
                }
            }
            neighbours.put(s.id(), List.copyOf(adjacent));
        }
        this.routes = new RoutePlanner(network, NOMINAL_DWELL);
        this.od = new OdMatrix(network, routes);
        int n = network.stations().size();
        for (int o = 0; o < n; o++) {
            Station s = network.stations().get(o);
            StationState st = new StationState(s.id());
            // A platform queue for every direction a train can leave in (a terminal only has one per line).
            for (String lid : linesByStation.getOrDefault(s.id(), List.of())) {
                Line line = lines.get(lid);
                int pos = line.stationIds().indexOf(s.id());
                if (pos < line.stationIds().size() - 1) {
                    st.waiting.put(new StationState.QueueKey(lid, 1), new double[n]);
                }
                if (pos > 0) {
                    st.waiting.put(new StationState.QueueKey(lid, -1), new double[n]);
                }
            }
            for (int d = 0; d < n; d++) {
                double share = od.share(o, d);
                if (share > 0) {
                    st.arrivalShare.computeIfAbsent(routes.entryQueue(o, d), k -> new double[n])[d] += share;
                }
            }
            stations.put(s.id(), st);
        }
        placeTrains();
    }

    private SimulationEngine(SimulationEngine other) {
        this.network = other.network;
        this.lines.putAll(other.lines);
        this.cumulativeStopSeconds.putAll(other.cumulativeStopSeconds);
        this.linesByStation.putAll(other.linesByStation);
        this.neighbours.putAll(other.neighbours);
        this.routes = other.routes;
        this.od = other.od;
        other.trains.forEach(t -> this.trains.add(t.copy()));
        other.stations.forEach((k, v) -> this.stations.put(k, v.copy()));
        this.events.addAll(other.events);
        this.overloadState.putAll(other.overloadState);
        other.closures.forEach(c -> this.closures.add(c.copy()));
        this.closedStations.putAll(other.closedStations);
        this.strategy = other.strategy;
        this.simTime = other.simTime;
        this.recordArrivals = other.recordArrivals;
        this.rideSeconds = other.rideSeconds;
    }

    /** Deep copy, used for counterfactual ("what-if") runs. */
    public SimulationEngine copy() {
        return new SimulationEngine(this);
    }

    /**
     * Spaces each line's trains evenly along its loop: train {@code i} starts exactly {@code i / n} of the
     * nominal loop time from stop 0, either dwelling at a platform or part-way through a segment. Snapping trains
     * to platforms instead would leave uneven gaps (and bunching) whenever the loop is not a whole multiple of
     * the headway, which skews any comparison against the initial state.
     */
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
                double offset = target - cum[stop];
                // Fixed-block signalling allows one train per platform-plus-segment block: if the even position
                // falls in an occupied block (headway shorter than a long block), start at the next platform.
                if (stop <= used) {
                    stop = used + 1;
                    offset = 0;
                }
                if (stop >= line.loopLength()) {
                    throw new IllegalStateException("Too many trains for line " + line.id());
                }
                used = stop;
                TrainState t = new TrainState(line.id() + "-" + (i + 1), line.id(), line.trainCapacity(),
                        network.stations().size());
                t.stop = stop;
                t.scheduledArrival = simTime - offset;
                if (offset < NOMINAL_DWELL) {
                    t.phase = TrainPhase.DWELLING;
                    t.phaseRemaining = NOMINAL_DWELL - offset;
                } else {
                    t.phase = TrainPhase.RUNNING;
                    t.segmentDuration = line.runSecondsFromStop(stop);
                    t.segmentElapsed = offset - NOMINAL_DWELL;
                }
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
        expireClosures(dt);
        accumulateDemand(dt);
        for (TrainState t : trains) {
            rideSeconds += t.passengers * dt;
            advance(t, dt);
        }
    }

    private void accumulateDemand(double dt) {
        double mult = DemandProfile.multiplier(simTime);
        double decay = Math.exp(-dt / TRANSFER_RATE_WINDOW_SECONDS);
        Map<String, Double> diverted = new LinkedHashMap<>();
        for (String closed : closedStations.keySet()) {
            divertToNeighbours(closed, network.station(closed).baseArrivalsPerMinute() * mult * dt / 60.0, diverted);
        }
        for (Station s : network.stations()) {
            StationState st = stations.get(s.id());
            if (st.waiting.isEmpty() || closedStations.containsKey(s.id())) {
                continue;
            }
            double entering = st.heldAtEntrance + s.baseArrivalsPerMinute() * mult * dt / 60.0
                    + diverted.getOrDefault(s.id(), 0.0);
            double admitted = s.hasPlatformLimit()
                    ? Math.min(entering, Math.max(0, s.platformCapacity() - st.totalWaiting()))
                    : entering;
            st.heldAtEntrance = entering - admitted;
            st.arrivalShare.forEach((key, share) -> {
                double[] queue = st.waiting.get(key);
                for (int d = 0; d < queue.length; d++) {
                    queue[d] += admitted * share[d];
                }
            });
            st.recentTransfers.replaceAll((key, v) -> v * decay);
            double total = st.totalWaiting();
            st.waitSeconds += (total + st.heldAtEntrance) * dt;
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

    /**
     * Applies the headway strategy and, at terminals, the timetable: a train that turns round early waits for its
     * planned departure (the layover gives recovery time), so earliness never carries over into the next trip.
     */
    private void decideHoldOrDepart(TrainState t) {
        Line line = lines.get(t.lineId);
        if (!isTerminalStop(line, t.stop) && closedStations.containsKey(line.stationAtStop(t.stop))) {
            t.holdDecided = true; // never hold a train at a closed station it is running through
        }
        if (!t.holdDecided) {
            t.holdDecided = true;
            double target = cumulativeStopSeconds.get(line.id())[line.loopLength()] / line.trainCount();
            double hold = strategy.holdSeconds(forwardHeadway(t), backwardHeadway(t), target);
            if (isTerminalStop(line, t.stop)) {
                hold = Math.max(hold, plannedDeparture(t) - simTime);
            }
            if (hold > 0.5) {
                t.phase = TrainPhase.HELD;
                t.phaseRemaining = hold;
                return;
            }
        }
        tryDepart(t);
    }

    private static boolean isTerminalStop(Line line, int stop) {
        return stop == 0 || stop == line.stationIds().size() - 1;
    }

    /** Timetabled departure from the train's current stop: planned arrival plus the nominal dwell. */
    double plannedDeparture(TrainState t) {
        return t.scheduledArrival + NOMINAL_DWELL;
    }

    private void tryDepart(TrainState t) {
        Line line = lines.get(t.lineId);
        SectionClosure closure = closureAhead(line, t.stop);
        if (closure != null) {
            if (closure.response != ClosureResponse.TURN_BACK || !tryTurnBack(t, line)) {
                t.phase = TrainPhase.WAITING_SIGNAL;
            }
            return;
        }
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
        t.phase = TrainPhase.DWELLING;
        t.holdDecided = false;
        if (closedStations.containsKey(stationId)) {
            // Doors stay shut: the train runs through without stopping.
            t.phaseRemaining = 0;
            return;
        }
        StationState st = stations.get(stationId);
        double alight = alight(t, line, st);
        double board = board(t, line, st);

        t.phaseRemaining = Math.max(MIN_DWELL,
                Math.min(MAX_DWELL, MIN_DWELL + SECONDS_PER_PASSENGER * (alight + board)));
    }

    /**
     * Passengers whose destination is this station leave the network; passengers whose route changes lines
     * here join the queue for their next line. Everyone else stays aboard. Passengers for a closed station that
     * the train has run through get off at the first open station after it and finish their trip on foot.
     */
    private double alight(TrainState t, Line line, StationState st) {
        int lineIdx = routes.lineIndex(line.id());
        int here = routes.index(st.id);
        boolean[] stays = routes.staysAboard(lineIdx, t.stop);
        double alight = 0;
        for (int d = 0; d < t.onboard.length; d++) {
            double amount = t.onboard[d];
            if (amount <= 0 || stays[d]) {
                continue;
            }
            t.onboard[d] = 0;
            alight += amount;
            boolean arrived = d == here || (!closedStations.isEmpty()
                    && closedStations.containsKey(network.stations().get(d).id()));
            StationState.QueueKey next = arrived ? null : routes.transferQueue(lineIdx, t.stop, d);
            if (next == null) {
                st.delivered += amount;
            } else {
                st.waiting.get(next)[d] += amount;
                st.transfers += amount;
                st.recentTransfers.merge(next, amount, Double::sum);
            }
        }
        t.passengers = StationState.sum(t.onboard);
        return alight;
    }

    /**
     * Boards the queue for the train's direction; if it does not fit, every destination boards pro rata. Nobody
     * boards a train that is about to turn back short of a closed section.
     */
    private double board(TrainState t, Line line, StationState st) {
        SectionClosure closure = closureAhead(line, t.stop);
        if (closure != null && closure.response == ClosureResponse.TURN_BACK) {
            return 0; // the train will reverse here; passengers keep waiting for the line to reopen
        }
        double[] queue = st.waiting.get(new StationState.QueueKey(t.lineId, line.directionAtStop(t.stop)));
        if (queue == null) {
            return 0;
        }
        double waiting = StationState.sum(queue);
        double room = Math.max(0, t.capacity - t.passengers);
        if (waiting <= 0) {
            return 0;
        }
        if (room <= 0) {
            st.leftBehind += waiting;
            return 0;
        }
        double fraction = Math.min(1, room / waiting);
        if (fraction < 1) {
            st.leftBehind += waiting - room;
        }
        double board = 0;
        for (int d = 0; d < queue.length; d++) {
            double b = queue[d] * fraction;
            queue[d] -= b;
            t.onboard[d] += b;
            board += b;
        }
        st.served += board;
        t.passengers = StationState.sum(t.onboard);
        return board;
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

    /**
     * Turns a train round at its current platform: it moves to the stop serving the same station in the opposite
     * direction, everyone on board gets off to wait for the original direction, and passengers for the new
     * direction board. Not possible at a terminal or while the opposite platform (or the track into it) is taken.
     */
    private boolean tryTurnBack(TrainState t, Line line) {
        if (isTerminalStop(line, t.stop)) {
            return false;
        }
        int mirror = line.loopLength() - t.stop;
        if (sectionClosed(line, mirror)) {
            return false; // closed on both sides: nowhere to go
        }
        int before = (mirror - 1 + line.loopLength()) % line.loopLength();
        for (TrainState o : trains) {
            if (o != t && o.lineId.equals(t.lineId)
                    && ((o.stop == mirror && o.phase != TrainPhase.RUNNING)
                    || (o.stop == before && o.phase == TrainPhase.RUNNING))) {
                return false;
            }
        }
        StationState st = stations.get(line.stationAtStop(t.stop));
        double[] queue = st.waiting.get(new StationState.QueueKey(t.lineId, line.directionAtStop(t.stop)));
        double alight = t.passengers;
        for (int d = 0; d < t.onboard.length; d++) {
            queue[d] += t.onboard[d];
            t.onboard[d] = 0;
        }
        t.passengers = 0;
        double[] cum = cumulativeStopSeconds.get(line.id());
        // Take over the timetable slot of the mirror stop; the train is now early, never later than before.
        t.scheduledArrival += cum[mirror] - cum[t.stop];
        t.stop = mirror;
        t.delaySeconds = Math.max(0, simTime - t.scheduledArrival);
        double board = board(t, line, st);
        t.phase = TrainPhase.DWELLING;
        t.holdDecided = false;
        t.phaseRemaining = Math.max(MIN_DWELL,
                Math.min(MAX_DWELL, MIN_DWELL + SECONDS_PER_PASSENGER * (alight + board)));
        return true;
    }

    /**
     * Closes the track between two adjacent stations of a line, in both directions, for {@code seconds}. Trains
     * due to enter the section wait at the platform before it (their signal stays red) until it reopens; a train
     * already inside the section completes its run.
     */
    public void closeSection(String lineId, String fromStationId, String toStationId, double seconds) {
        closeSection(lineId, fromStationId, toStationId, seconds, ClosureResponse.WAIT);
    }

    /**
     * Closes a track section as {@link #closeSection(String, String, String, double)} does, with trains reacting
     * as {@code response} says. Closing a section that is already closed extends the closure and updates the
     * response.
     */
    public void closeSection(String lineId, String fromStationId, String toStationId, double seconds,
                             ClosureResponse response) {
        Line line = lines.get(lineId);
        if (line == null) {
            throw new IllegalArgumentException("Unknown line " + lineId);
        }
        int a = line.stationIds().indexOf(fromStationId);
        int b = line.stationIds().indexOf(toStationId);
        if (a < 0 || b < 0 || Math.abs(a - b) != 1) {
            throw new IllegalArgumentException(
                    "Stations %s and %s are not adjacent on line %s".formatted(fromStationId, toStationId, lineId));
        }
        if (seconds <= 0) {
            throw new IllegalArgumentException("Closure duration must be positive");
        }
        int lower = Math.min(a, b);
        SectionClosure existing = closures.stream()
                .filter(c -> c.lineId.equals(lineId) && c.lowerPosition == lower).findFirst().orElse(null);
        if (existing != null) {
            existing.remainingSeconds = Math.max(existing.remainingSeconds, seconds);
            existing.response = response;
        } else {
            closures.add(new SectionClosure(lineId, lower, response, seconds));
        }
        emit(SimEvent.Type.INCIDENT, lineId, "Track %s–%s on line %s closed for %.0f s".formatted(
                sectionName(line, lower), sectionName(line, lower + 1), lineId, seconds));
    }

    private String sectionName(Line line, int position) {
        return network.station(line.stationIds().get(position)).name();
    }

    /** Whether the segment a train takes when leaving loop stop {@code stop} is closed. */
    boolean sectionClosed(Line line, int stop) {
        return closureAhead(line, stop) != null;
    }

    /** Closure of the segment a train takes when leaving loop stop {@code stop}, or null if it is open. */
    private SectionClosure closureAhead(Line line, int stop) {
        if (closures.isEmpty()) {
            return null;
        }
        int n = line.stationIds().size();
        int pos = stop < n ? stop : line.loopLength() - stop;
        int lower = line.directionAtStop(stop) > 0 ? pos : pos - 1;
        for (SectionClosure c : closures) {
            if (c.lineId.equals(line.id()) && c.lowerPosition == lower) {
                return c;
            }
        }
        return null;
    }

    private void expireClosures(double dt) {
        for (var it = closures.iterator(); it.hasNext(); ) {
            SectionClosure c = it.next();
            c.remainingSeconds -= dt;
            if (c.remainingSeconds <= 1e-9) {
                it.remove();
                Line line = lines.get(c.lineId);
                emit(SimEvent.Type.INCIDENT_CLEARED, c.lineId, "Track %s–%s on line %s reopened".formatted(
                        sectionName(line, c.lowerPosition), sectionName(line, c.lowerPosition + 1), c.lineId));
            }
        }
        for (var it = closedStations.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            e.setValue(e.getValue() - dt);
            if (e.getValue() <= 1e-9) {
                it.remove();
                emit(SimEvent.Type.INCIDENT_CLEARED, e.getKey(),
                        "Station " + network.station(e.getKey()).name() + " reopened");
            }
        }
    }

    /**
     * Closes a station for {@code seconds}: trains run through it without stopping (they still reverse at a
     * closed terminal) and nobody can enter or leave it there. Passengers already inside, and new ones arriving
     * while it is closed, go to the neighbouring stations instead. Closing a closed station extends the closure.
     */
    public void closeStation(String stationId, double seconds) {
        Station station = network.station(stationId);
        if (seconds <= 0) {
            throw new IllegalArgumentException("Closure duration must be positive");
        }
        if (!closedStations.containsKey(stationId)) {
            evacuate(stations.get(stationId));
        }
        closedStations.merge(stationId, seconds, Math::max);
        emit(SimEvent.Type.INCIDENT, stationId,
                "Station %s closed for %.0f s".formatted(station.name(), seconds));
    }

    /**
     * Clears a closing station: everyone on its platforms or at its entrance walks to a neighbouring open station
     * and queues at its entrance. In the fluid model they then travel like that station's own passengers.
     */
    private void evacuate(StationState st) {
        double people = st.totalWaiting() + st.heldAtEntrance;
        for (double[] q : st.waiting.values()) {
            java.util.Arrays.fill(q, 0);
        }
        st.heldAtEntrance = 0;
        Map<String, Double> moved = new LinkedHashMap<>();
        divertToNeighbours(st.id, people, moved);
        moved.forEach((sid, amount) -> stations.get(sid).heldAtEntrance += amount);
    }

    /**
     * Splits {@code passengers} evenly between the open neighbours of a closed station. If every neighbour is
     * closed too, they give up on the metro.
     */
    private void divertToNeighbours(String stationId, double passengers, Map<String, Double> into) {
        List<String> open = neighbours.get(stationId).stream()
                .filter(n -> !closedStations.containsKey(n)).toList();
        for (String n : open) {
            into.merge(n, passengers / open.size(), Double::sum);
        }
    }

    public boolean stationClosed(String stationId) {
        return closedStations.containsKey(stationId);
    }

    // ------------------------------------------------------------------ prediction

    /**
     * Predicts how many passengers will be waiting at a station after {@code horizon} seconds. Each platform
     * queue (line + direction) is forecast separately: current queue + expected arrivals (new trips routed to
     * this queue plus recent transfers into it) − the share of free space of the trains that will call at the
     * station in that direction within the horizon. A train travelling the other way cannot relieve a queue, so
     * a surplus in one direction never offsets a backlog in the other. Passengers held at the entrance of a full
     * station are added on top: they are waiting too, and will only reach the platform as it clears.
     */
    public double predictWaiting(String stationId, double horizon) {
        StationState st = stations.get(stationId);
        double mult = DemandProfile.multiplier(simTime + horizon / 2);
        double predicted = 0;
        for (StationState.QueueKey key : st.waiting.keySet()) {
            Line line = lines.get(key.lineId());
            double rate = boardingRate(st, key, mult);
            double lineRate = 0;
            for (String sid : line.stationIds()) {
                lineRate += boardingRate(stations.get(sid), key, mult);
            }
            // A train's spare room is shared by every station it serves in this direction, roughly in proportion
            // to demand, so this queue can only count on its share of it.
            double share = lineRate > 0 ? rate / lineRate : 0;
            double expected = st.waiting(key) + rate * horizon;
            for (TrainState t : trains) {
                if (!t.lineId.equals(key.lineId())) {
                    continue;
                }
                int stop = nextCallStop(t, line, stationId, key.direction());
                if (stop >= 0 && secondsUntilStop(t, line, stop) <= horizon) {
                    double residual = Math.max(0, t.capacity - passengersAboardAfter(t, line, stop));
                    expected -= residual * share;
                }
            }
            predicted += Math.max(0, expected);
        }
        return predicted + st.heldAtEntrance;
    }

    /** Expected passengers per second joining queue {@code key} at a station (0 if it has no such queue). */
    private double boardingRate(StationState st, StationState.QueueKey key, double demandMultiplier) {
        if (!st.waiting.containsKey(key)) {
            return 0;
        }
        double newTrips = network.station(st.id).baseArrivalsPerMinute() * demandMultiplier / 60.0
                * st.arrivalShare(key);
        double transfers = st.recentTransfers.getOrDefault(key, 0.0) / TRANSFER_RATE_WINDOW_SECONDS;
        return newTrips + transfers;
    }

    /** Loop stop at which {@code t} next calls at {@code stationId} ready to leave in {@code direction}. */
    private int nextCallStop(TrainState t, Line line, String stationId, int direction) {
        double[] cum = cumulativeStopSeconds.get(line.id());
        double total = cum[line.loopLength()];
        double pos = loopPosition(t);
        double best = Double.MAX_VALUE;
        int bestStop = -1;
        for (int k = 0; k < line.loopLength(); k++) {
            if (line.stationAtStop(k).equals(stationId) && line.directionAtStop(k) == direction) {
                double d = cum[k] - pos;
                if (d <= 0) {
                    d += total;
                }
                if (d < best) {
                    best = d;
                    bestStop = k;
                }
            }
        }
        return bestStop;
    }

    /** Nominal seconds until {@code t} reaches loop stop {@code stop}, plus any time it is blocked. */
    private double secondsUntilStop(TrainState t, Line line, int stop) {
        double[] cum = cumulativeStopSeconds.get(line.id());
        double d = cum[stop] - loopPosition(t);
        if (d <= 0) {
            d += cum[line.loopLength()];
        }
        return d + Math.max(0, t.blockedRemaining);
    }

    /**
     * Passengers currently on {@code t} who will still be aboard after it has called at loop stop {@code stop}
     * (everyone alighting at or before that stop frees their place).
     */
    double passengersAboardAfter(TrainState t, Line line, int stop) {
        int lineIdx = routes.lineIndex(line.id());
        boolean[] aboard = new boolean[t.onboard.length];
        java.util.Arrays.fill(aboard, true);
        int k = t.stop;
        do {
            k = (k + 1) % line.loopLength();
            boolean[] stays = routes.staysAboard(lineIdx, k);
            for (int d = 0; d < aboard.length; d++) {
                aboard[d] &= stays[d];
            }
        } while (k != stop);
        double remaining = 0;
        for (int d = 0; d < aboard.length; d++) {
            if (aboard[d]) {
                remaining += t.onboard[d];
            }
        }
        return remaining;
    }

    // ------------------------------------------------------------------ timetable

    /**
     * Departure board for a station: every train due to leave it within {@code horizon} seconds, in either
     * direction, with its timetabled and expected departure, soonest expected first. A train counts once per
     * call, at the first time it leaves the station. A closed station's board is empty: trains run through.
     */
    public List<Departure> departures(String stationId, double horizon) {
        network.station(stationId); // validate
        List<Departure> board = new ArrayList<>();
        if (closedStations.containsKey(stationId)) {
            return board; // trains run through without stopping
        }
        for (TrainState t : trains) {
            Line line = lines.get(t.lineId);
            double[] cum = cumulativeStopSeconds.get(line.id());
            double loop = cum[line.loopLength()];
            int best = -1;
            double bestAhead = Double.MAX_VALUE;
            for (int k = 0; k < line.loopLength(); k++) {
                if (!line.stationAtStop(k).equals(stationId)) {
                    continue;
                }
                double ahead = cum[k] - cum[t.stop];
                boolean atPlatform = t.phase != TrainPhase.RUNNING && k == t.stop;
                if (ahead < 0 || (ahead == 0 && !atPlatform)) {
                    ahead += loop;
                }
                if (ahead < bestAhead) {
                    bestAhead = ahead;
                    best = k;
                }
            }
            if (best < 0) {
                continue;
            }
            double planned = plannedDeparture(t) + bestAhead;
            double expected = bestAhead == 0
                    ? simTime + Math.max(0, t.blockedRemaining) + remainingAtPlatform(t)
                    : simTime + secondsUntilStop(t, line, best) + NOMINAL_DWELL;
            if (expected - simTime > horizon) {
                continue;
            }
            List<String> ids = line.stationIds();
            String towards = line.directionAtStop(best) > 0 ? ids.get(ids.size() - 1) : ids.get(0);
            board.add(new Departure(t.id, line.id(), towards, planned, clock(planned), expected, expected - planned));
        }
        board.sort(Comparator.comparingDouble(Departure::expectedTime));
        return board;
    }

    /** Seconds before a train standing at a platform is free to leave (ignoring signals). */
    private double remainingAtPlatform(TrainState t) {
        double remaining = t.phase == TrainPhase.WAITING_SIGNAL ? 0 : Math.max(0, t.phaseRemaining);
        if (!t.holdDecided && isTerminalStop(lines.get(t.lineId), t.stop)) {
            remaining = Math.max(remaining, plannedDeparture(t) - simTime);
        }
        return remaining;
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
                queues.add(new SimulationSnapshot.QueueView(key.lineId(), towards, StationState.sum(waiting)));
            });
            stationViews.add(new SimulationSnapshot.StationView(st.id, st.totalWaiting(), predicted,
                    riskLevel(predicted), st.heldAtEntrance, st.leftBehind, queues,
                    closedStations.containsKey(st.id)));
        }
        return new SimulationSnapshot(simTime, clock(simTime), strategy.name(), trainViews, stationViews,
                List.copyOf(events), metrics(), closedSections());
    }

    private List<SimulationSnapshot.ClosedSectionView> closedSections() {
        List<SimulationSnapshot.ClosedSectionView> views = new ArrayList<>();
        for (SectionClosure c : closures) {
            List<String> ids = lines.get(c.lineId).stationIds();
            views.add(new SimulationSnapshot.ClosedSectionView(c.lineId, ids.get(c.lowerPosition),
                    ids.get(c.lowerPosition + 1), c.response, c.remainingSeconds));
        }
        return views;
    }

    private SimulationSnapshot.Metrics metrics() {
        double avg = trains.stream().mapToDouble(t -> t.delaySeconds).average().orElse(0);
        double max = trains.stream().mapToDouble(t -> t.delaySeconds).max().orElse(0);
        double waiting = stations.values().stream().mapToDouble(StationState::totalWaiting).sum();
        double served = stations.values().stream().mapToDouble(s -> s.served).sum();
        double delivered = stations.values().stream().mapToDouble(s -> s.delivered).sum();
        double transfers = stations.values().stream().mapToDouble(s -> s.transfers).sum();
        double leftBehind = stations.values().stream().mapToDouble(s -> s.leftBehind).sum();
        double held = stations.values().stream().mapToDouble(s -> s.heldAtEntrance).sum();
        return new SimulationSnapshot.Metrics(avg, max, waiting, served, delivered, transfers, leftBehind, held,
                headwayCv());
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

    /** Passenger-seconds spent on board trains so far (running, dwelling, held or stuck). */
    public double passengerRideSeconds() { return rideSeconds; }
    public double stationPeakWaiting(String stationId) { return stations.get(stationId).peakWaiting; }
    public double stationWaiting(String stationId) { return stations.get(stationId).totalWaiting(); }

    /**
     * Passengers waiting at a station for one line and direction (+1 towards the line's last station,
     * -1 towards its first). Returns 0 for a queue that does not exist, e.g. outbound at the last station.
     */
    public double stationWaiting(String stationId, String lineId, int direction) {
        return stations.get(stationId).waiting(new StationState.QueueKey(lineId, direction));
    }

    /** Passengers waiting at a station for one line and direction who are heading to {@code destinationId}. */
    public double stationWaiting(String stationId, String lineId, int direction, String destinationId) {
        double[] queue = stations.get(stationId).waiting.get(new StationState.QueueKey(lineId, direction));
        return queue == null ? 0 : queue[routes.index(destinationId)];
    }

    /** Passengers on board {@code trainId} heading to {@code destinationId}. */
    public double trainPassengersTo(String trainId, String destinationId) {
        return train(trainId).onboard[routes.index(destinationId)];
    }

    /** Fraction of trips entering at {@code originId} that travel to {@code destinationId}. */
    public double odShare(String originId, String destinationId) {
        return od.share(routes.index(originId), routes.index(destinationId));
    }

    /** Lines a passenger from {@code originId} to {@code destinationId} rides, in order. */
    public List<String> route(String originId, String destinationId) {
        return routes.linesRidden(routes.index(originId), routes.index(destinationId));
    }

    public double stationDelivered(String stationId) { return stations.get(stationId).delivered; }
    public double stationTransfers(String stationId) { return stations.get(stationId).transfers; }
    public double stationLeftBehind(String stationId) { return stations.get(stationId).leftBehind; }
    public double stationHeldAtEntrance(String stationId) { return stations.get(stationId).heldAtEntrance; }
}
