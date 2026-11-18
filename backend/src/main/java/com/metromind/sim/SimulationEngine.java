package com.metromind.sim;

import com.metromind.network.Branch;
import com.metromind.network.Line;
import com.metromind.network.LineTrack;
import com.metromind.network.MetroNetwork;
import com.metromind.network.ServicePattern;
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
    /** Short turns kept in {@link #shortTurns()}; older ones are only counted. */
    static final int MAX_SHORT_TURN_LOG = 1000;
    /** Skipped stops kept in {@link #skippedStops()}; older ones are only counted. */
    static final int MAX_SKIPPED_STOP_LOG = 1000;
    /** Departures from the end of a shared trunk kept per direction to measure {@link #trunkHeadwayCv()}. */
    static final int TRUNK_SAMPLE_DEPARTURES = 8;
    /** How often strategies that dispatch from the depot review each line's fleet, in simulated seconds. */
    static final double DISPATCH_INTERVAL_SECONDS = 60;
    /** Horizon of the overload forecast that triggers dispatching. */
    static final double DISPATCH_FORECAST_SECONDS = 900;
    /** Lateness from which strategies that short-turn trains consider reversing one early. */
    static final double SHORT_TURN_LATENESS_SECONDS = 240;
    /** How far ahead the demand forecast handed to {@link HeadwayStrategy#targetHeadway} looks. */
    static final double HOLD_DEMAND_FORECAST_SECONDS = 300;
    /**
     * A train is only short-turned when its follower is closer than this fraction of the target headway, so the
     * stations it abandons are served again soon.
     */
    static final double SHORT_TURN_FOLLOWER_FRACTION = 0.5;
    /** Lateness from which strategies that skip stops let a train run through a quiet station. */
    static final double SKIP_STOP_LATENESS_SECONDS = 120;
    /**
     * A stop counts as quiet when fewer passengers than this would board or alight there; skipping a busier one
     * would cost its passengers more than the train recovers.
     */
    static final double SKIP_STOP_MAX_PASSENGERS = 40;
    /**
     * A stop is only skipped when the gap to the train behind is shorter than this fraction of the gap ahead: the
     * train is lagging between its neighbours (lateness alone is common to every train in a busy peak), and its
     * follower will soon serve the platform.
     */
    static final double SKIP_STOP_FOLLOWER_FRACTION = 0.8;

    private final MetroNetwork network;
    private final Map<String, Line> lines = new LinkedHashMap<>();
    /** Service patterns by id, in network order; a train's loop stops index the pattern it follows. */
    private final Map<String, ServicePattern> patterns = new LinkedHashMap<>();
    private final Map<String, List<ServicePattern>> patternsByLine = new LinkedHashMap<>();
    /** Per service pattern: nominal seconds from loop stop 0 to each stop, run plus dwell (last = whole loop). */
    private final Map<String, double[]> cumulativeStopSeconds = new LinkedHashMap<>();
    /**
     * Track alignments of each line, main route first, then its branches, where the network file gives them; trains
     * elsewhere run on the chord between stations.
     */
    private final Map<String, List<LineTrack>> tracks = new LinkedHashMap<>();
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
    /** Simulated time at which each currently closed station was closed. */
    private final Map<String, Double> stationClosedSince = new LinkedHashMap<>();
    /** Spare trains waiting in each line's depot. */
    private final Map<String, Integer> depot = new LinkedHashMap<>();
    /** Trains each line has brought out of its depot so far; numbers new trains so ids are never reused. */
    private final Map<String, Integer> dispatchedCount = new LinkedHashMap<>();
    private double nextDispatchCheck;
    private HeadwayStrategy strategy;
    private double simTime;
    private boolean recordArrivals;
    /** Integral of passengers on board over time, all trains (passenger-seconds). */
    private double rideSeconds;
    /** Stops trains have run through under a skip-stop strategy so far. */
    private int stopsSkipped;
    /** Most recent short turns, oldest first, at most {@link #MAX_SHORT_TURN_LOG}. */
    private final Deque<ShortTurn> shortTurnLog = new ArrayDeque<>();
    /** Most recent skipped stops, oldest first, at most {@link #MAX_SKIPPED_STOP_LOG}. */
    private final Deque<SkippedStop> skippedStopLog = new ArrayDeque<>();
    /** Last station of each branched line's shared trunk: the first junction along its main route. */
    private final Map<String, String> trunkEnds = new LinkedHashMap<>();
    /** Recent departure times from each trunk end, keyed by line and direction (see {@link #trunkKey}). */
    private final Map<String, Deque<Double>> trunkDepartures = new LinkedHashMap<>();
    /** Flat junctions of every line, where trains leaving for a branch cross the opposite main track. */
    private final List<FlatCrossing> flatCrossings = new ArrayList<>();
    /** Simulated time until which a train leaving for a branch occupies each flat crossing (by its key). */
    private final Map<String, Double> crossingBusyUntil = new LinkedHashMap<>();

    /**
     * A flat junction: a train running from {@code junctionId} to the branch's first station
     * {@code branchFirstId} crosses the track that main-route trains run on from {@code mainNextId} back into the
     * junction, and occupies the crossing for its first {@code seconds}; a main-route train occupies it for its
     * last {@code seconds} before the junction platform.
     */
    private record FlatCrossing(String lineId, String junctionId, String branchFirstId, String mainNextId,
                                double seconds) {
        String key() {
            return lineId + "@" + junctionId;
        }
    }

    public SimulationEngine(MetroNetwork network, double startTime, HeadwayStrategy strategy) {
        this.network = network;
        this.simTime = startTime;
        this.strategy = strategy;
        for (Line line : network.lines()) {
            lines.put(line.id(), line);
            patternsByLine.put(line.id(), line.patterns());
            line.stationIds().stream()
                    .filter(id -> line.branches().stream().anyMatch(b -> b.junctionStationId().equals(id)))
                    .findFirst().ifPresent(junction -> trunkEnds.put(line.id(), junction));
            for (Branch b : line.branches()) {
                if (b.flatJunction()) {
                    String mainNext = line.stationIds().get(line.stationIds().indexOf(b.junctionStationId()) + 1);
                    flatCrossings.add(new FlatCrossing(line.id(), b.junctionStationId(), b.stationIds().get(0),
                            mainNext, b.crossingSeconds()));
                }
            }
            for (ServicePattern p : line.patterns()) {
                patterns.put(p.id(), p);
                double[] cum = new double[p.loopLength() + 1];
                for (int k = 0; k < p.loopLength(); k++) {
                    cum[k + 1] = cum[k] + p.runSecondsFromStop(k) + NOMINAL_DWELL;
                }
                cumulativeStopSeconds.put(p.id(), cum);
            }
            List<LineTrack> own = new ArrayList<>();
            java.util.Optional.ofNullable(LineTrack.of(line, network)).ifPresent(own::add);
            line.branches().forEach(b -> java.util.Optional.ofNullable(LineTrack.of(b, network)).ifPresent(own::add));
            tracks.put(line.id(), List.copyOf(own));
            depot.put(line.id(), line.spareTrains());
            dispatchedCount.put(line.id(), 0);
            for (String sid : line.allStationIds()) {
                linesByStation.computeIfAbsent(sid, k -> new ArrayList<>()).add(line.id());
            }
        }
        for (Station s : network.stations()) {
            List<String> adjacent = new ArrayList<>();
            for (Station other : network.stations()) {
                boolean next = patterns.values().stream().anyMatch(p ->
                        p.sectionBetween(p.stationIds().indexOf(s.id()), p.stationIds().indexOf(other.id())) >= 0);
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
                for (int dir : new int[] {1, -1}) {
                    for (ServicePattern p : patternsByLine.get(lid)) {
                        int pos = p.stationIds().indexOf(s.id());
                        if (pos >= 0 && p.nextPosition(pos, dir) >= 0) {
                            st.waiting.putIfAbsent(new StationState.QueueKey(lid, dir), new double[n]);
                        }
                    }
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
        this.nextDispatchCheck = startTime + DISPATCH_INTERVAL_SECONDS;
        placeTrains();
    }

    private SimulationEngine(SimulationEngine other) {
        this.network = other.network;
        this.lines.putAll(other.lines);
        this.patterns.putAll(other.patterns);
        this.patternsByLine.putAll(other.patternsByLine);
        this.cumulativeStopSeconds.putAll(other.cumulativeStopSeconds);
        this.tracks.putAll(other.tracks);
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
        this.stationClosedSince.putAll(other.stationClosedSince);
        this.depot.putAll(other.depot);
        this.dispatchedCount.putAll(other.dispatchedCount);
        this.nextDispatchCheck = other.nextDispatchCheck;
        this.strategy = other.strategy;
        this.simTime = other.simTime;
        this.recordArrivals = other.recordArrivals;
        this.rideSeconds = other.rideSeconds;
        this.stopsSkipped = other.stopsSkipped;
        this.shortTurnLog.addAll(other.shortTurnLog);
        this.skippedStopLog.addAll(other.skippedStopLog);
        this.trunkEnds.putAll(other.trunkEnds);
        this.flatCrossings.addAll(other.flatCrossings);
        this.crossingBusyUntil.putAll(other.crossingBusyUntil);
        other.trunkDepartures.forEach((k, v) -> this.trunkDepartures.put(k, new ArrayDeque<>(v)));
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
            List<ServicePattern> own = patternsByLine.get(line.id());
            java.util.Set<String> taken = new java.util.HashSet<>();
            Map<String, String> fedFrom = new java.util.HashMap<>();
            int number = 0;
            for (int q = 0; q < own.size(); q++) {
                number = placeTrains(line, own.get(q), (double) q / own.size(), taken, fedFrom, number);
            }
        }
    }

    /**
     * Places the trains of one service pattern; returns the last train number used on the line. A branched line's
     * patterns are staggered by {@code phase} of their own headway, so their trains alternate on the trunk.
     *
     * @param taken   blocks (station and direction of departure) already holding a train of the line
     * @param fedFrom for each platform holding a train or with one running into it, the station that train
     *                comes from; where a branch joins the trunk no second train may approach from the other side
     */
    private int placeTrains(Line line, ServicePattern p, double phase, java.util.Set<String> taken,
                            Map<String, String> fedFrom, int number) {
        double[] cum = cumulativeStopSeconds.get(p.id());
        double loop = cum[p.loopLength()];
        for (int i = 0; i < p.trainCount(); i++) {
            double target = (i + phase) * loop / p.trainCount();
            int stop = 0;
            while (stop + 1 < p.loopLength() && cum[stop + 1] <= target) {
                stop++;
            }
            double offset = target - cum[stop];
            // Fixed-block signalling allows one train per platform-plus-segment block: if the even position
            // falls in an occupied block (headway shorter than a long block, or a train of another pattern on the
            // shared trunk), start at the next free platform, going on round the loop if need be.
            int tries = 0;
            while (taken.contains(blockKey(p, stop))
                    || !approachFree(p, offset >= NOMINAL_DWELL ? stop + 1 : stop, fedFrom)) {
                if (++tries >= p.loopLength()) {
                    throw new IllegalStateException("Too many trains for line " + line.id());
                }
                stop = (stop + 1) % p.loopLength();
                offset = 0;
            }
            taken.add(blockKey(p, stop));
            int platform = offset >= NOMINAL_DWELL ? stop + 1 : stop;
            fedFrom.put(blockKey(p, platform), approachFrom(p, platform));
            TrainState t = new TrainState(line.id() + "-" + (++number), line.id(), p.id(),
                    line.trainCapacity(), network.stations().size());
            t.stop = stop;
            t.scheduledArrival = simTime - offset;
            if (offset < NOMINAL_DWELL) {
                t.phase = TrainPhase.DWELLING;
                t.phaseRemaining = NOMINAL_DWELL - offset;
            } else {
                t.phase = TrainPhase.RUNNING;
                t.segmentDuration = p.runSecondsFromStop(stop);
                t.segmentElapsed = offset - NOMINAL_DWELL;
            }
            trains.add(t);
        }
        return number;
    }

    /** Whether no train of another approach holds or is heading for the platform of stop {@code k} of {@code p}. */
    private static boolean approachFree(ServicePattern p, int k, Map<String, String> fedFrom) {
        String from = fedFrom.get(blockKey(p, k));
        return from == null || from.equals(approachFrom(p, k));
    }

    /** Station a train of {@code p} comes from when it arrives at loop stop {@code k}. */
    private static String approachFrom(ServicePattern p, int k) {
        return p.stationAtStop(Math.floorMod(k - 1, p.loopLength()));
    }

    /** The block a train at loop stop {@code k} of {@code p} holds: its platform and the track leaving it. */
    private static String blockKey(ServicePattern p, int k) {
        k %= p.loopLength();
        return p.stationAtStop(k) + "|" + p.directionAtStop(k);
    }

    private ServicePattern pattern(TrainState t) {
        return patterns.get(t.patternId);
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
        if (simTime >= nextDispatchCheck - 1e-9) {
            while (simTime >= nextDispatchCheck - 1e-9) {
                nextDispatchCheck += DISPATCH_INTERVAL_SECONDS;
            }
            if (strategy.dispatchesFromDepot()) {
                reviewFleets();
            }
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
                t.segmentElapsed = advanceToCrossing(t, t.segmentElapsed + dt);
                if (t.segmentElapsed >= t.segmentDuration) {
                    arrive(t);
                }
            }
        }
    }

    /**
     * Applies the headway strategy and, at timing points, the timetable: a train that turns round early waits for
     * its planned departure (the layover gives recovery time), so earliness never carries over into the next trip.
     */
    private void decideHoldOrDepart(TrainState t) {
        ServicePattern p = pattern(t);
        if (!p.isTerminalStop(t.stop) && closedStations.containsKey(p.stationAtStop(t.stop))) {
            t.holdDecided = true; // never hold a train at a closed station it is running through
        }
        if (!t.holdDecided) {
            t.holdDecided = true;
            double target = loopTotal(p) / trainsOnPattern(p.id());
            if (shouldShortTurn(t, p, target)) {
                String stationId = p.stationAtStop(t.stop);
                double lateness = t.delaySeconds;
                if (tryTurnBack(t, p)) {
                    emit(SimEvent.Type.TRAIN_SHORT_TURNED, t.id, "Train %s, %.0f s late, short-turned at %s"
                            .formatted(t.id, lateness, network.station(stationId).name()));
                    shortTurnLog.addLast(new ShortTurn(t.id, t.lineId, stationId, simTime, lateness));
                    while (shortTurnLog.size() > MAX_SHORT_TURN_LOG) {
                        shortTurnLog.removeFirst();
                    }
                    return; // dwelling again on the opposite platform; the hold is decided when it is ready
                }
            }
            // Regulate the service passengers on the platform see: on the trunk of a branched line that is the
            // combined headway of every pattern calling there, so trains of different patterns do not bunch.
            double demand = DemandProfile.multiplier(simTime + HOLD_DEMAND_FORECAST_SECONDS);
            double hold = strategy.holdSeconds(platformForwardHeadway(t), platformBackwardHeadway(t),
                    strategy.targetHeadway(platformEvenHeadway(t), demand));
            if (p.isTimingStop(t.stop)) {
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

    /**
     * Whether to reverse a late train at its current intermediate platform: the strategy allows it, the train is
     * at least {@link #SHORT_TURN_LATENESS_SECONDS} late, the station is open and the train behind is bunched up
     * close enough to serve the stations this one gives up.
     */
    private boolean shouldShortTurn(TrainState t, ServicePattern p, double targetHeadway) {
        return strategy.shortTurnsLateTrains()
                && !p.ring()
                && !p.isTerminalStop(t.stop)
                && t.delaySeconds >= SHORT_TURN_LATENESS_SECONDS
                && !closedStations.containsKey(p.stationAtStop(t.stop))
                && trainsOnPattern(p.id()) > 1
                && backwardHeadway(t) < SHORT_TURN_FOLLOWER_FRACTION * targetHeadway;
    }

    /**
     * Whether a train that has just reached a platform should run through it instead of calling: the strategy
     * allows it, the train is at least {@link #SKIP_STOP_LATENESS_SECONDS} late, the stop is an intermediate one
     * that is not an interchange (skipping it would strand transfers), the train did not skip its previous stop,
     * it is lagging between its neighbours (see {@link #SKIP_STOP_FOLLOWER_FRACTION}), and fewer than
     * {@link #SKIP_STOP_MAX_PASSENGERS} passengers would board or alight here. Under a strategy that also
     * short-turns, a train late enough to be short-turned never skips: it calls so it can turn back here (except
     * on a ring, where nothing turns back).
     */
    private boolean shouldSkipStop(TrainState t, ServicePattern p, StationState st) {
        if (!strategy.skipsStopsForLateTrains()
                || t.delaySeconds < SKIP_STOP_LATENESS_SECONDS
                || (strategy.shortTurnsLateTrains() && !p.ring()
                    && t.delaySeconds >= SHORT_TURN_LATENESS_SECONDS)
                || t.skippedLastStop
                || p.isTerminalStop(t.stop)
                || linesByStation.get(st.id).size() > 1
                || trainsOnPattern(p.id()) < 2) {
            return false;
        }
        if (backwardHeadway(t) >= SKIP_STOP_FOLLOWER_FRACTION * forwardHeadway(t)) {
            return false;
        }
        double[] queue = st.waiting.get(new StationState.QueueKey(t.lineId, p.directionAtStop(t.stop)));
        double boarding = queue == null ? 0 : StationState.sum(queue);
        return boarding + alightingAt(t) < SKIP_STOP_MAX_PASSENGERS;
    }

    /** Passengers on {@code t} who would leave the train if it called at its current stop. */
    private double alightingAt(TrainState t) {
        boolean[] stays = routes.staysAboard(routes.patternIndex(t.patternId), t.stop);
        double alighting = 0;
        for (int d = 0; d < t.onboard.length; d++) {
            if (!stays[d]) {
                alighting += t.onboard[d];
            }
        }
        return alighting;
    }

    /** Timetabled departure from the train's current stop: planned arrival plus the nominal dwell. */
    double plannedDeparture(TrainState t) {
        return t.scheduledArrival + NOMINAL_DWELL;
    }

    private void tryDepart(TrainState t) {
        ServicePattern p = pattern(t);
        SectionClosure closure = closureAhead(p, t.stop);
        if (closure != null) {
            if (closure.response != ClosureResponse.TURN_BACK || !tryTurnBack(t, p)) {
                t.phase = TrainPhase.WAITING_SIGNAL;
            }
            return;
        }
        int next = (t.stop + 1) % p.loopLength();
        String to = p.stationAtStop(next);
        if (sectionTaken(t, t.lineId, p.stationAtStop(t.stop), to)
                || platformTaken(t, t.lineId, to, p.directionAtStop(next))) {
            t.phase = TrainPhase.WAITING_SIGNAL;
            return;
        }
        FlatCrossing crossing = crossing(t.lineId, p.stationAtStop(t.stop), to, true);
        if (crossing != null) {
            if (crossingApproached(crossing)) {
                t.phase = TrainPhase.WAITING_SIGNAL;
                return;
            }
            crossingBusyUntil.put(crossing.key(), simTime + crossing.seconds());
        }
        t.phase = TrainPhase.RUNNING;
        t.segmentElapsed = 0;
        t.segmentDuration = p.runSecondsFromStop(t.stop);
        recordTrunkDeparture(t, p);
    }

    private void arrive(TrainState t) {
        ServicePattern p = pattern(t);
        t.scheduledArrival += p.runSecondsFromStop(t.stop) + NOMINAL_DWELL;
        t.stop = (t.stop + 1) % p.loopLength();
        t.delaySeconds = Math.max(0, simTime - t.scheduledArrival);
        if (recordArrivals) {
            t.arrivals.add(simTime);
        }
        detectDelay(t);

        String stationId = p.stationAtStop(t.stop);
        t.phase = TrainPhase.DWELLING;
        t.holdDecided = false;
        if (closedStations.containsKey(stationId)) {
            // Doors stay shut: the train runs through without stopping.
            t.phaseRemaining = 0;
            return;
        }
        StationState st = stations.get(stationId);
        if (shouldSkipStop(t, p, st)) {
            t.phaseRemaining = 0;
            t.holdDecided = true; // the point is to catch up, so no headway hold either
            t.skippedLastStop = true;
            stopsSkipped++;
            skippedStopLog.addLast(new SkippedStop(t.id, t.lineId, stationId, simTime, t.delaySeconds,
                    alightingAt(t)));
            while (skippedStopLog.size() > MAX_SKIPPED_STOP_LOG) {
                skippedStopLog.removeFirst();
            }
            emit(SimEvent.Type.TRAIN_SKIPPED_STOP, t.id, "Train %s, %.0f s late, ran through %s"
                    .formatted(t.id, t.delaySeconds, network.station(stationId).name()));
            return;
        }
        t.skippedLastStop = false;
        double alight = alight(t, st);
        double board = board(t, p, st);

        t.phaseRemaining = Math.max(MIN_DWELL,
                Math.min(MAX_DWELL, MIN_DWELL + SECONDS_PER_PASSENGER * (alight + board)));
    }

    /**
     * Passengers whose destination is this station leave the network; passengers whose route changes lines
     * here join the queue for their next line. Everyone else stays aboard. Passengers for a closed station that
     * the train has run through get off at the first open station after it and finish their trip on foot.
     */
    private double alight(TrainState t, StationState st) {
        int patternIdx = routes.patternIndex(t.patternId);
        int here = routes.index(st.id);
        boolean[] stays = routes.staysAboard(patternIdx, t.stop);
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
            StationState.QueueKey next = arrived ? null : routes.transferQueue(patternIdx, t.stop, d);
            if (next == null) {
                st.delivered += amount;
            } else {
                st.waiting.get(next)[d] += amount;
                // Going back along the same line to a station this train serves only happens after being carried
                // past a skipped stop; staying on a branched line for a station it does not serve is a change of
                // route at the junction.
                if (next.lineId().equals(t.lineId) && routes.serves(patternIdx, d)) {
                    st.carriedPast += amount;
                } else {
                    st.transfers += amount;
                }
                st.recentTransfers.merge(next, amount, Double::sum);
            }
        }
        t.passengers = StationState.sum(t.onboard);
        return alight;
    }

    /**
     * Boards the queue for the train's direction: everyone whose route this train serves (on a branched line,
     * passengers for the other branch wait for their own train). If they do not fit, every destination boards pro
     * rata. Nobody boards a train that is about to turn back short of a closed section.
     */
    private double board(TrainState t, ServicePattern p, StationState st) {
        SectionClosure closure = closureAhead(p, t.stop);
        if (closure != null && closure.response == ClosureResponse.TURN_BACK) {
            return 0; // the train will reverse here; passengers keep waiting for the line to reopen
        }
        double[] queue = st.waiting.get(new StationState.QueueKey(t.lineId, p.directionAtStop(t.stop)));
        if (queue == null) {
            return 0;
        }
        boolean[] boards = routes.boards(routes.patternIndex(t.patternId), t.stop);
        double waiting = 0;
        for (int d = 0; d < queue.length; d++) {
            if (boards[d]) {
                waiting += queue[d];
            }
        }
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
            if (!boards[d]) {
                continue;
            }
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

    /** Position of a train along its pattern's loop, in nominal seconds since stop 0. */
    double loopPosition(TrainState t) {
        double[] cum = cumulativeStopSeconds.get(t.patternId);
        double base = cum[t.stop];
        if (t.phase == TrainPhase.RUNNING) {
            return base + NOMINAL_DWELL + t.segmentElapsed;
        }
        return base;
    }

    /** Nominal time round a pattern's loop, dwells included. */
    private double loopTotal(ServicePattern p) {
        return cumulativeStopSeconds.get(p.id())[p.loopLength()];
    }

    /** Nominal gap to the train ahead on the same service pattern (the whole loop if there is none). */
    double forwardHeadway(TrainState t) {
        double total = loopTotal(pattern(t));
        double pos = loopPosition(t);
        double best = total;
        for (TrainState o : trains) {
            if (o != t && o.patternId.equals(t.patternId)) {
                double d = Math.floorMod((long) Math.round((loopPosition(o) - pos) * 1000), (long) (total * 1000)) / 1000.0;
                if (d > 0 && d < best) {
                    best = d;
                }
            }
        }
        return best;
    }

    /** Nominal gap to the train behind on the same service pattern (the whole loop if there is none). */
    double backwardHeadway(TrainState t) {
        double total = loopTotal(pattern(t));
        double pos = loopPosition(t);
        double best = total;
        for (TrainState o : trains) {
            if (o != t && o.patternId.equals(t.patternId)) {
                double d = Math.floorMod((long) Math.round((pos - loopPosition(o)) * 1000), (long) (total * 1000)) / 1000.0;
                if (d > 0 && d < best) {
                    best = d;
                }
            }
        }
        return best;
    }

    /*
     * Platform headways: the gaps a passenger on t's platform sees, counting every train of the line that calls
     * there in t's direction, whatever its service pattern. On an unbranched line they equal the pattern headways
     * above; on the trunk of a branched line they measure how evenly the patterns run together.
     */

    /** Loop stop of {@code p} at which trains call at {@code stationId} leaving in {@code direction}, or -1. */
    private static int callingStop(ServicePattern p, String stationId, int direction) {
        for (int k = 0; k < p.loopLength(); k++) {
            if (p.directionAtStop(k) == direction && p.stationAtStop(k).equals(stationId)) {
                return k;
            }
        }
        return -1;
    }

    /**
     * Nominal gap from the platform where {@code t} stands to the nearest other train of its line, ahead of it
     * ({@code ahead}: seconds since that train left the platform) or behind it (seconds until it gets there).
     * Only trains whose pattern calls at the platform count; with none, the whole loop of {@code t}'s pattern.
     */
    private double platformGap(TrainState t, boolean ahead) {
        ServicePattern own = pattern(t);
        String stationId = own.stationAtStop(t.stop);
        int direction = own.directionAtStop(t.stop);
        double best = loopTotal(own);
        for (TrainState o : trains) {
            if (o == t || !o.lineId.equals(t.lineId)) {
                continue;
            }
            ServicePattern op = pattern(o);
            int k = callingStop(op, stationId, direction);
            if (k < 0) {
                continue;
            }
            double total = loopTotal(op);
            double diff = ahead ? loopPosition(o) - cumulativeStopSeconds.get(op.id())[k]
                    : cumulativeStopSeconds.get(op.id())[k] - loopPosition(o);
            double d = Math.floorMod(Math.round(diff * 1000), (long) (total * 1000)) / 1000.0;
            if (d > 0 && d < best) {
                best = d;
            }
        }
        return best;
    }

    /** Nominal gap to the last train of the line, of any pattern, to leave the platform where {@code t} is. */
    double platformForwardHeadway(TrainState t) {
        return platformGap(t, true);
    }

    /** Nominal gap to the next train of the line, of any pattern, due at the platform where {@code t} is. */
    double platformBackwardHeadway(TrainState t) {
        return platformGap(t, false);
    }

    /**
     * Even headway at the platform where {@code t} is: the combined service of every pattern calling there, each
     * running its trains evenly round its own loop ({@code 1 / Σ trains / loop time}). On a stretch served by one
     * pattern it is that pattern's loop time divided by its trains.
     */
    double platformEvenHeadway(TrainState t) {
        ServicePattern own = pattern(t);
        String stationId = own.stationAtStop(t.stop);
        int direction = own.directionAtStop(t.stop);
        double trainsPerSecond = 0;
        for (ServicePattern p : patternsByLine.get(t.lineId)) {
            if (callingStop(p, stationId, direction) >= 0) {
                long running = trains.stream().filter(o -> o.patternId.equals(p.id())).count();
                trainsPerSecond += running / loopTotal(p);
            }
        }
        return 1 / trainsPerSecond; // t's own pattern calls here, so the sum is positive
    }

    // ------------------------------------------------------------------ signalling

    /*
     * Fixed blocks are physical track, not loop stops, so trains of every service pattern of a line exclude each
     * other on the trunk they share: a platform is a station's track in one direction of travel, and a section is
     * the track from one station to the next in one direction.
     */

    /**
     * Whether a train of line {@code lineId} other than {@code self} stands at, or is running into, the platform
     * of {@code stationId} from which trains leave in {@code direction} (at a terminal: the platform trains
     * reverse at). Where a branch joins the trunk two sections lead into the same platform, so a train already on
     * its way in from the other one holds it too.
     */
    private boolean platformTaken(TrainState self, String lineId, String stationId, int direction) {
        for (TrainState o : trains) {
            if (o != self && o.lineId.equals(lineId)) {
                ServicePattern op = pattern(o);
                int k = o.phase == TrainPhase.RUNNING ? (o.stop + 1) % op.loopLength() : o.stop;
                if (op.stationAtStop(k).equals(stationId) && op.directionAtStop(k) == direction) {
                    return true;
                }
            }
        }
        return false;
    }

    /*
     * Flat junctions: a train leaving the junction for the branch and a main-route train running back into the
     * junction use the same piece of track where their paths cross, so only one of them may be on it. The
     * main-route train has priority once it is within the crossing time of the platform; otherwise the branch
     * train goes first and the main-route train stops short of the crossing until it is clear.
     */

    /**
     * The flat crossing a run from {@code from} to {@code to} on line {@code lineId} uses, or null: as the train
     * leaving for the branch ({@code diverging}) or as the main-route train running into the junction.
     */
    private FlatCrossing crossing(String lineId, String from, String to, boolean diverging) {
        for (FlatCrossing c : flatCrossings) {
            if (c.lineId().equals(lineId) && (diverging
                    ? c.junctionId().equals(from) && c.branchFirstId().equals(to)
                    : c.mainNextId().equals(from) && c.junctionId().equals(to))) {
                return c;
            }
        }
        return null;
    }

    /** Whether a main-route train is already within the crossing time of the junction, i.e. on the crossing. */
    private boolean crossingApproached(FlatCrossing c) {
        for (TrainState o : trains) {
            if (o.phase == TrainPhase.RUNNING && o.lineId.equals(c.lineId())) {
                ServicePattern op = pattern(o);
                if (op.stationAtStop(o.stop).equals(c.mainNextId())
                        && op.stationAtStop((o.stop + 1) % op.loopLength()).equals(c.junctionId())
                        && o.segmentDuration - o.segmentElapsed <= c.seconds()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How far along its run {@code t} gets when it would reach {@code elapsed}: a main-route train running into a
     * flat junction stops at the crossing while a train leaving for the branch is on it.
     */
    private double advanceToCrossing(TrainState t, double elapsed) {
        if (flatCrossings.isEmpty()) {
            return elapsed;
        }
        ServicePattern p = pattern(t);
        FlatCrossing c = crossing(t.lineId, p.stationAtStop(t.stop), p.stationAtStop((t.stop + 1) % p.loopLength()),
                false);
        if (c == null || crossingBusyUntil.getOrDefault(c.key(), Double.NEGATIVE_INFINITY) <= simTime) {
            return elapsed;
        }
        double signal = t.segmentDuration - c.seconds();
        return elapsed > signal ? Math.max(t.segmentElapsed, signal) : elapsed;
    }

    /** Whether a train of line {@code lineId} other than {@code self} is running from {@code from} to {@code to}. */
    private boolean sectionTaken(TrainState self, String lineId, String from, String to) {
        for (TrainState o : trains) {
            if (o != self && o.phase == TrainPhase.RUNNING && o.lineId.equals(lineId)) {
                ServicePattern op = pattern(o);
                if (op.stationAtStop(o.stop).equals(from)
                        && op.stationAtStop((o.stop + 1) % op.loopLength()).equals(to)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ incidents

    /** Holds a train in place for {@code seconds} (e.g. door fault, passenger incident). */
    public void injectDelay(String trainId, double seconds) {
        TrainState t = train(trainId);
        startBlock(t);
        t.blockedRemaining += seconds;
        emit(SimEvent.Type.INCIDENT, trainId, "Train %s delayed by %.0f s".formatted(trainId, seconds));
    }

    /** Technical failure: the train stops where it is and blocks the track until repaired. */
    public void injectBreakdown(String trainId, double repairSeconds) {
        TrainState t = train(trainId);
        startBlock(t);
        t.blockedRemaining += repairSeconds;
        t.failed = true;
        emit(SimEvent.Type.INCIDENT, trainId, "Train %s broke down, repair ETA %.0f s".formatted(trainId, repairSeconds));
    }

    /** Records when a train's current stoppage began, unless it is already stopped by an earlier incident. */
    private void startBlock(TrainState t) {
        if (t.blockedRemaining <= 0) {
            t.blockedRemaining = 0;
            t.blockedSince = simTime;
        }
    }

    /**
     * Turns a train round at its current platform: it moves to the stop serving the same station in the opposite
     * direction, everyone on board gets off to wait for the original direction, and passengers for the new
     * direction board. Not possible at a terminal, on a ring (its trains only run one way) or while the opposite
     * platform (or the track into it) is taken.
     */
    private boolean tryTurnBack(TrainState t, ServicePattern p) {
        if (p.ring() || p.isTerminalStop(t.stop)) {
            return false;
        }
        int mirror = p.loopLength() - t.stop;
        if (sectionClosed(p, mirror)) {
            return false; // closed on both sides: nowhere to go
        }
        int before = (mirror - 1 + p.loopLength()) % p.loopLength();
        String here = p.stationAtStop(t.stop);
        if (platformTaken(t, t.lineId, here, p.directionAtStop(mirror))
                || sectionTaken(t, t.lineId, p.stationAtStop(before), here)) {
            return false;
        }
        StationState st = stations.get(p.stationAtStop(t.stop));
        double[] queue = st.waiting.get(new StationState.QueueKey(t.lineId, p.directionAtStop(t.stop)));
        double alight = t.passengers;
        for (int d = 0; d < t.onboard.length; d++) {
            queue[d] += t.onboard[d];
            t.onboard[d] = 0;
        }
        t.passengers = 0;
        double[] cum = cumulativeStopSeconds.get(p.id());
        // Take over the timetable slot of the mirror stop; the train is now early, never later than before. Turning
        // back from the inbound half the mirror stop belongs to the next trip round the loop, not the last one.
        double ahead = cum[mirror] - cum[t.stop];
        if (ahead < 0) {
            ahead += cum[p.loopLength()];
        }
        t.scheduledArrival += ahead;
        t.stop = mirror;
        t.delaySeconds = Math.max(0, simTime - t.scheduledArrival);
        double board = board(t, p, st);
        t.phase = TrainPhase.DWELLING;
        t.holdDecided = false;
        t.phaseRemaining = Math.max(MIN_DWELL,
                Math.min(MAX_DWELL, MIN_DWELL + SECONDS_PER_PASSENGER * (alight + board)));
        return true;
    }

    /**
     * Closes the track between two adjacent stations of a line, in both directions, for {@code seconds}. The
     * stations may be on a branch; a section of the trunk is closed for trains of every service pattern. Trains
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
        if (!lines.containsKey(lineId)) {
            throw new IllegalArgumentException("Unknown line " + lineId);
        }
        String[] ends = null;
        for (ServicePattern p : patternsByLine.get(lineId)) {
            int lower = p.sectionBetween(p.stationIds().indexOf(fromStationId), p.stationIds().indexOf(toStationId));
            if (lower >= 0) {
                ends = new String[] {p.stationIds().get(lower), p.stationIds().get(p.sectionEnd(lower))};
                break;
            }
        }
        if (ends == null) {
            throw new IllegalArgumentException(
                    "Stations %s and %s are not adjacent on line %s".formatted(fromStationId, toStationId, lineId));
        }
        if (seconds <= 0) {
            throw new IllegalArgumentException("Closure duration must be positive");
        }
        String from = ends[0];
        String to = ends[1];
        SectionClosure existing = closures.stream()
                .filter(c -> c.covers(lineId, from, to)).findFirst().orElse(null);
        if (existing != null) {
            existing.remainingSeconds = Math.max(existing.remainingSeconds, seconds);
            existing.response = response;
        } else {
            closures.add(new SectionClosure(lineId, from, to, response, seconds, simTime));
        }
        emit(SimEvent.Type.INCIDENT, lineId, "Track %s–%s on line %s closed for %.0f s".formatted(
                stationName(from), stationName(to), lineId, seconds));
    }

    private String stationName(String stationId) {
        return network.station(stationId).name();
    }

    /** Whether the segment a train of the line's main pattern takes when leaving loop stop {@code stop} is closed. */
    boolean sectionClosed(Line line, int stop) {
        return sectionClosed(patternsByLine.get(line.id()).get(0), stop);
    }

    /** Whether the segment a train of pattern {@code p} takes when leaving loop stop {@code stop} is closed. */
    boolean sectionClosed(ServicePattern p, int stop) {
        return closureAhead(p, stop) != null;
    }

    /** Closure of the segment a train takes when leaving loop stop {@code stop}, or null if it is open. */
    private SectionClosure closureAhead(ServicePattern p, int stop) {
        if (closures.isEmpty()) {
            return null;
        }
        String from = p.stationAtStop(stop);
        String to = p.stationAtStop((stop + 1) % p.loopLength());
        for (SectionClosure c : closures) {
            if (c.covers(p.lineId(), from, to)) {
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
                emit(SimEvent.Type.INCIDENT_CLEARED, c.lineId, "Track %s–%s on line %s reopened".formatted(
                        stationName(c.fromStationId), stationName(c.toStationId), c.lineId));
            }
        }
        for (var it = closedStations.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            e.setValue(e.getValue() - dt);
            if (e.getValue() <= 1e-9) {
                it.remove();
                stationClosedSince.remove(e.getKey());
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
            stationClosedSince.put(stationId, simTime);
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

    // ------------------------------------------------------------------ depot

    /**
     * Brings a spare train into service on every line whose overload forecast is high, one train per line and
     * review. Trains enter at the first terminal, next to the depot, once its platform and the track into it
     * are clear. When a line running more than its base fleet is forecast below the medium level, a train that
     * is standing at the first terminal goes back to the depot instead.
     */
    private void reviewFleets() {
        for (Line line : lines.values()) {
            double forecast = lineForecast(line);
            if (depot.get(line.id()) > 0 && forecast >= OVERLOAD_HIGH && depotExitClear(line)) {
                dispatch(line, mostOverloadedPattern(line));
            } else if (forecast < OVERLOAD_MEDIUM && trainsInService(line.id()) > line.trainCount()) {
                withdrawAtDepot(line);
            }
        }
    }

    /**
     * Sends back to the depot a train standing at the first terminal, if there is one and it is free to move.
     * Anyone already aboard for the outbound trip steps off and waits for the next train.
     */
    private void withdrawAtDepot(Line line) {
        for (TrainState t : trains) {
            if (t.lineId.equals(line.id()) && t.stop == 0 && t.phase != TrainPhase.RUNNING
                    && t.blockedRemaining <= 0) {
                StationState st = stations.get(line.stationIds().get(0));
                double[] queue = st.waiting.get(new StationState.QueueKey(line.id(), line.directionAtStop(0)));
                for (int d = 0; d < t.onboard.length; d++) {
                    queue[d] += t.onboard[d];
                }
                trains.remove(t);
                depot.merge(line.id(), 1, Integer::sum);
                emit(SimEvent.Type.TRAIN_WITHDRAWN, t.id, "Train %s returned to the depot of line %s".formatted(
                        t.id, line.id()));
                return;
            }
        }
    }

    /**
     * Highest 15-minute forecast among the line's own platform queues, so overload on a crossing line does not
     * draw trains onto this one. Closed stations are left out: no train can relieve them.
     */
    double lineForecast(Line line) {
        double mult = DemandProfile.multiplier(simTime + DISPATCH_FORECAST_SECONDS / 2);
        double worst = 0;
        for (String sid : line.allStationIds()) {
            if (closedStations.containsKey(sid)) {
                continue;
            }
            StationState st = stations.get(sid);
            double atStation = 0;
            for (StationState.QueueKey key : st.waiting.keySet()) {
                if (key.lineId().equals(line.id())) {
                    atStation += predictQueue(st, key, DISPATCH_FORECAST_SECONDS, mult);
                }
            }
            worst = Math.max(worst, atStation);
        }
        return worst;
    }

    /**
     * Highest 15-minute forecast of the passengers that trains of pattern {@code p} would take, over the line's
     * stations: a passenger bound for a branch only weighs on that branch's pattern, one bound for a trunk station
     * on every pattern. Closed stations are left out.
     */
    double patternForecast(Line line, ServicePattern p) {
        double mult = DemandProfile.multiplier(simTime + DISPATCH_FORECAST_SECONDS / 2);
        int bit = 1 << patternsByLine.get(line.id()).indexOf(p);
        double worst = 0;
        for (String sid : line.allStationIds()) {
            if (closedStations.containsKey(sid)) {
                continue;
            }
            StationState st = stations.get(sid);
            double atStation = 0;
            for (StationState.QueueKey key : st.waiting.keySet()) {
                if (key.lineId().equals(line.id())) {
                    for (Map.Entry<Integer, Double> g
                            : predictQueueGroups(st, key, DISPATCH_FORECAST_SECONDS, mult).entrySet()) {
                        if ((g.getKey() & bit) != 0) {
                            atStation += g.getValue();
                        }
                    }
                }
            }
            worst = Math.max(worst, atStation);
        }
        return worst;
    }

    /** The service pattern a spare train should run: the one with the worst forecast, the main route on a tie. */
    private ServicePattern mostOverloadedPattern(Line line) {
        List<ServicePattern> own = patternsByLine.get(line.id());
        ServicePattern best = own.get(0);
        if (own.size() == 1) {
            return best;
        }
        double worst = patternForecast(line, best);
        for (ServicePattern p : own.subList(1, own.size())) {
            double forecast = patternForecast(line, p);
            if (forecast > worst) {
                worst = forecast;
                best = p;
            }
        }
        return best;
    }

    private boolean depotExitClear(Line line) {
        if (closedStations.containsKey(line.stationIds().get(0))) {
            return false;
        }
        String first = line.stationIds().get(0);
        return !platformTaken(null, line.id(), first, 1)
                && !sectionTaken(null, line.id(), line.stationIds().get(1), first);
    }

    /**
     * Puts a spare train on the first terminal's platform, with its own timetable starting now. It runs service
     * pattern {@code pattern}; every pattern of a line starts at its first terminal, next to the depot.
     */
    private void dispatch(Line line, ServicePattern pattern) {
        int number = line.trainCount() + dispatchedCount.merge(line.id(), 1, Integer::sum);
        TrainState t = new TrainState(line.id() + "-" + number, line.id(), pattern.id(), line.trainCapacity(),
                network.stations().size());
        t.stop = 0;
        t.scheduledArrival = simTime;
        t.phase = TrainPhase.DWELLING;
        double board = board(t, pattern, stations.get(line.stationIds().get(0)));
        t.phaseRemaining = Math.max(MIN_DWELL, Math.min(MAX_DWELL, MIN_DWELL + SECONDS_PER_PASSENGER * board));
        trains.add(t);
        depot.merge(line.id(), -1, Integer::sum);
        String towards = network.station(pattern.towardsStationId(0, 1)).name();
        emit(SimEvent.Type.TRAIN_DISPATCHED, t.id, "Train %s left the depot to relieve line %s towards %s"
                .formatted(t.id, line.id(), towards));
    }

    /** Trains currently running on a line (at least 1, so it can divide a loop time). */
    int trainsInService(String lineId) {
        int n = 0;
        for (TrainState t : trains) {
            if (t.lineId.equals(lineId)) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    /** Trains currently following a service pattern (at least 1, so it can divide a loop time). */
    int trainsOnPattern(String patternId) {
        int n = 0;
        for (TrainState t : trains) {
            if (t.patternId.equals(patternId)) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    public int depotTrains(String lineId) {
        return depot.getOrDefault(lineId, 0);
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
            predicted += predictQueue(st, key, horizon, mult);
        }
        return predicted + st.heldAtEntrance;
    }

    /**
     * Forecast for one platform queue of a station; see {@link #predictWaiting(String, double)}. On the trunk of a
     * branched line not every train takes every passenger: someone bound for the branch waits for a branch train.
     * The queue is therefore split by the set of service patterns that would board each passenger, and a train's
     * free space only relieves the groups its pattern boards, in proportion to their demand. On an unbranched line
     * every train boards everyone, so there is one group.
     */
    private double predictQueue(StationState st, StationState.QueueKey key, double horizon, double mult) {
        double expected = 0;
        for (double left : predictQueueGroups(st, key, horizon, mult).values()) {
            expected += left;
        }
        return expected;
    }

    /**
     * Forecast of one platform queue per group of passengers, keyed by the bit mask (over the line's service
     * patterns) of the patterns whose trains would board them; see {@link #predictQueue}.
     */
    private Map<Integer, Double> predictQueueGroups(StationState st, StationState.QueueKey key, double horizon,
                                                    double mult) {
        Line line = lines.get(key.lineId());
        double rate = boardingRate(st, key, mult);
        double lineRate = 0;
        for (String sid : line.allStationIds()) {
            lineRate += boardingRate(stations.get(sid), key, mult);
        }
        // A train's spare room is shared by every station it serves in this direction, roughly in proportion
        // to demand, so this queue can only count on its share of it.
        double share = lineRate > 0 ? rate / lineRate : 0;
        Map<Integer, Double> demand = groupDemand(st, key, horizon, mult);
        Map<Integer, Double> relief = new java.util.HashMap<>();
        List<ServicePattern> own = patternsByLine.get(key.lineId());
        for (TrainState t : trains) {
            if (!t.lineId.equals(key.lineId())) {
                continue;
            }
            ServicePattern p = pattern(t);
            int stop = nextCallStop(t, p, st.id, key.direction());
            if (stop < 0 || secondsUntilStop(t, p, stop) > horizon) {
                continue;
            }
            double residual = Math.max(0, t.capacity - passengersAboardAfter(t, p, stop)) * share;
            int bit = 1 << own.indexOf(p);
            double eligible = 0;
            for (Map.Entry<Integer, Double> g : demand.entrySet()) {
                if ((g.getKey() & bit) != 0) {
                    eligible += g.getValue();
                }
            }
            if (eligible <= 0) {
                continue;
            }
            for (Map.Entry<Integer, Double> g : demand.entrySet()) {
                if ((g.getKey() & bit) != 0) {
                    relief.merge(g.getKey(), residual * g.getValue() / eligible, Double::sum);
                }
            }
        }
        Map<Integer, Double> left = new java.util.TreeMap<>();
        for (Map.Entry<Integer, Double> g : demand.entrySet()) {
            left.put(g.getKey(), Math.max(0, g.getValue() - relief.getOrDefault(g.getKey(), 0.0)));
        }
        return left;
    }

    /**
     * Passengers expected in queue {@code key} over the horizon (waiting now plus arrivals), grouped by the set of
     * the line's service patterns whose trains would board them, as a bit mask over {@code patternsByLine}. Transfer
     * inflow is split like the passengers queueing now, or like new arrivals when the queue is empty.
     */
    private Map<Integer, Double> groupDemand(StationState st, StationState.QueueKey key, double horizon,
                                             double mult) {
        double[] queued = st.waiting.get(key);
        double[] arrivals = st.arrivalShare.getOrDefault(key, new double[queued.length]);
        double queuedTotal = StationState.sum(queued);
        double arrivalTotal = StationState.sum(arrivals);
        double perSecond = network.station(st.id).baseArrivalsPerMinute() * mult / 60.0;
        double transfers = st.recentTransfers.getOrDefault(key, 0.0) / TRANSFER_RATE_WINDOW_SECONDS;
        List<ServicePattern> own = patternsByLine.get(key.lineId());
        List<boolean[]> boards = new ArrayList<>();
        int all = 0;
        for (int i = 0; i < own.size(); i++) {
            int stop = callingStop(own.get(i), st.id, key.direction());
            boards.add(stop < 0 ? null : routes.boards(routes.patternIndex(own.get(i).id()), stop));
            all |= stop < 0 ? 0 : 1 << i;
        }
        Map<Integer, Double> demand = new java.util.TreeMap<>();
        for (int d = 0; d < queued.length; d++) {
            double transferShare = queuedTotal > 0 ? queued[d] / queuedTotal
                    : arrivalTotal > 0 ? arrivals[d] / arrivalTotal : 0;
            double amount = queued[d] + (perSecond * arrivals[d] + transfers * transferShare) * horizon;
            if (amount <= 0) {
                continue;
            }
            int mask = 0;
            for (int i = 0; i < own.size(); i++) {
                if (boards.get(i) != null && boards.get(i)[d]) {
                    mask |= 1 << i;
                }
            }
            demand.merge(mask, amount, Double::sum);
        }
        if (queuedTotal <= 0 && arrivalTotal <= 0 && transfers > 0) {
            demand.merge(all, transfers * horizon, Double::sum); // nothing says where they go: any train will do
        }
        return demand;
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
    private int nextCallStop(TrainState t, ServicePattern p, String stationId, int direction) {
        double[] cum = cumulativeStopSeconds.get(p.id());
        double total = cum[p.loopLength()];
        double pos = loopPosition(t);
        double best = Double.MAX_VALUE;
        int bestStop = -1;
        for (int k = 0; k < p.loopLength(); k++) {
            if (p.stationAtStop(k).equals(stationId) && p.directionAtStop(k) == direction) {
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
    private double secondsUntilStop(TrainState t, ServicePattern p, int stop) {
        double[] cum = cumulativeStopSeconds.get(p.id());
        double d = cum[stop] - loopPosition(t);
        if (d <= 0) {
            d += cum[p.loopLength()];
        }
        return d + Math.max(0, t.blockedRemaining);
    }

    /**
     * Passengers currently on {@code t} who will still be aboard after it has called at loop stop {@code stop}
     * (everyone alighting at or before that stop frees their place).
     */
    double passengersAboardAfter(TrainState t, ServicePattern p, int stop) {
        int patternIdx = routes.patternIndex(t.patternId);
        boolean[] aboard = new boolean[t.onboard.length];
        java.util.Arrays.fill(aboard, true);
        int k = t.stop;
        do {
            k = (k + 1) % p.loopLength();
            boolean[] stays = routes.staysAboard(patternIdx, k);
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
            ServicePattern p = pattern(t);
            double[] cum = cumulativeStopSeconds.get(p.id());
            double loop = cum[p.loopLength()];
            int best = -1;
            double bestAhead = Double.MAX_VALUE;
            for (int k = 0; k < p.loopLength(); k++) {
                if (!p.stationAtStop(k).equals(stationId)) {
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
                    : simTime + secondsUntilStop(t, p, best) + NOMINAL_DWELL;
            if (expected - simTime > horizon) {
                continue;
            }
            String towards = p.towardsStationId(p.positionAtStop(best), p.directionAtStop(best));
            board.add(new Departure(t.id, t.lineId, towards, planned, clock(planned), expected, expected - planned));
        }
        board.sort(Comparator.comparingDouble(Departure::expectedTime));
        return board;
    }

    /** Seconds before a train standing at a platform is free to leave (ignoring signals). */
    private double remainingAtPlatform(TrainState t) {
        double remaining = t.phase == TrainPhase.WAITING_SIGNAL ? 0 : Math.max(0, t.phaseRemaining);
        if (!t.holdDecided && pattern(t).isTimingStop(t.stop)) {
            remaining = Math.max(remaining, plannedDeparture(t) - simTime);
        }
        return remaining;
    }

    /**
     * Terminal the trains boarding queue {@code key} at {@code stationId} are signed towards: that of the first
     * service pattern of the line leaving the station in the queue's direction.
     */
    private String towards(StationState.QueueKey key, String stationId) {
        for (ServicePattern p : patternsByLine.get(key.lineId())) {
            int pos = p.stationIds().indexOf(stationId);
            if (pos >= 0 && p.nextPosition(pos, key.direction()) >= 0) {
                return p.towardsStationId(pos, key.direction());
            }
        }
        throw new IllegalStateException("No train of line " + key.lineId() + " leaves " + stationId);
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
            ServicePattern p = pattern(t);
            Station from = network.station(p.stationAtStop(t.stop));
            Station to = network.station(p.stationAtStop((t.stop + 1) % p.loopLength()));
            double progress = t.phase == TrainPhase.RUNNING ? Math.min(1, t.segmentElapsed / t.segmentDuration) : 0;
            LineTrack track = tracks.get(t.lineId).stream().filter(tr -> tr.covers(from.id(), to.id()))
                    .findFirst().orElse(null);
            double[] latLon = track != null
                    ? track.position(from.id(), to.id(), progress)
                    : new double[]{from.lat() + (to.lat() - from.lat()) * progress,
                                   from.lon() + (to.lon() - from.lon()) * progress};
            trainViews.add(new SimulationSnapshot.TrainView(t.id, t.lineId, t.patternId, from.id(), to.id(), progress,
                    latLon[0], latLon[1],
                    t.phase, t.delaySeconds, t.passengers, t.capacity, t.failed));
        }
        List<SimulationSnapshot.StationView> stationViews = new ArrayList<>();
        for (StationState st : stations.values()) {
            double predicted = predictWaiting(st.id, 900);
            List<SimulationSnapshot.QueueView> queues = new ArrayList<>();
            st.waiting.forEach((key, waiting) -> {
                String towards = towards(key, st.id);
                queues.add(new SimulationSnapshot.QueueView(key.lineId(), towards, StationState.sum(waiting)));
            });
            stationViews.add(new SimulationSnapshot.StationView(st.id, st.totalWaiting(), predicted,
                    riskLevel(predicted), st.heldAtEntrance, st.leftBehind, queues,
                    closedStations.containsKey(st.id)));
        }
        return new SimulationSnapshot(simTime, clock(simTime), strategy.name(), trainViews, stationViews,
                List.copyOf(events), metrics(), closedSections(), activeIncidents());
    }

    /**
     * Every incident still in force, soonest to clear first: stopped trains (delays and breakdowns), closed track
     * sections and closed stations. Ties keep the order above, so the list is deterministic.
     */
    List<SimulationSnapshot.ActiveIncidentView> activeIncidents() {
        List<SimulationSnapshot.ActiveIncidentView> views = new ArrayList<>();
        for (TrainState t : trains) {
            if (t.blockedRemaining > 0) {
                var kind = t.failed ? SimulationSnapshot.IncidentKind.BREAKDOWN
                        : SimulationSnapshot.IncidentKind.TRAIN_DELAY;
                String what = t.failed ? "Train %s broken down" : "Train %s held";
                views.add(incidentView(kind, t.id, what.formatted(t.id), t.blockedSince, t.blockedRemaining));
            }
        }
        for (SectionClosure c : closures) {
            views.add(incidentView(SimulationSnapshot.IncidentKind.SECTION_CLOSURE, c.lineId,
                    "Track %s–%s on line %s closed".formatted(stationName(c.fromStationId),
                            stationName(c.toStationId), c.lineId),
                    c.startedAt, c.remainingSeconds));
        }
        closedStations.forEach((id, remaining) -> views.add(incidentView(
                SimulationSnapshot.IncidentKind.STATION_CLOSURE, id,
                "Station " + network.station(id).name() + " closed",
                stationClosedSince.getOrDefault(id, simTime), remaining)));
        views.sort(Comparator.comparingDouble(SimulationSnapshot.ActiveIncidentView::remainingSeconds));
        return views;
    }

    private SimulationSnapshot.ActiveIncidentView incidentView(SimulationSnapshot.IncidentKind kind, String subject,
                                                               String description, double since, double remaining) {
        return new SimulationSnapshot.ActiveIncidentView(kind, subject, description, since, simTime + remaining,
                remaining);
    }

    private List<SimulationSnapshot.ClosedSectionView> closedSections() {
        List<SimulationSnapshot.ClosedSectionView> views = new ArrayList<>();
        for (SectionClosure c : closures) {
            views.add(new SimulationSnapshot.ClosedSectionView(c.lineId, c.fromStationId, c.toStationId, c.response,
                    c.remainingSeconds));
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
        double carriedPast = stations.values().stream().mapToDouble(s -> s.carriedPast).sum();
        double leftBehind = stations.values().stream().mapToDouble(s -> s.leftBehind).sum();
        double held = stations.values().stream().mapToDouble(s -> s.heldAtEntrance).sum();
        int inDepot = depot.values().stream().mapToInt(Integer::intValue).sum();
        return new SimulationSnapshot.Metrics(avg, max, waiting, served, delivered, transfers, leftBehind, held,
                headwayCv(), trains.size(), inDepot, stopsSkipped, carriedPast, trunkHeadwayCv());
    }

    /**
     * Coefficient of variation of headways across all service patterns (each line, or each route of a branched
     * line): 0 = perfectly even, higher = bunching.
     */
    public double headwayCv() {
        List<Double> cvs = new ArrayList<>();
        for (ServicePattern p : patterns.values()) {
            List<TrainState> lt = trains.stream().filter(t -> t.patternId.equals(p.id()))
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

    /**
     * Regularity of the combined service on each branched line's shared trunk: the coefficient of variation of the
     * gaps between the last {@link #TRUNK_SAMPLE_DEPARTURES} departures, by trains of any pattern, from the trunk's
     * last station (the first junction), averaged over both directions. {@link #headwayCv()} looks at each pattern
     * on its own, so trains of two patterns running as a pair on the trunk do not show there; they do here. A
     * direction with fewer than three departures so far does not count; unbranched lines have no entry.
     */
    public Map<String, Double> trunkHeadwayCv() {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Line line : network.lines()) {
            if (line.branches().isEmpty()) {
                continue;
            }
            double sum = 0;
            int directions = 0;
            for (int direction : new int[] {1, -1}) {
                Deque<Double> departures = trunkDepartures.get(trunkKey(line.id(), direction));
                if (departures == null || departures.size() < 3) {
                    continue;
                }
                double[] gaps = new double[departures.size() - 1];
                Double previous = null;
                int i = 0;
                for (double at : departures) {
                    if (previous != null) {
                        gaps[i++] = at - previous;
                    }
                    previous = at;
                }
                double mean = java.util.Arrays.stream(gaps).average().orElse(0);
                double var = java.util.Arrays.stream(gaps).map(g -> (g - mean) * (g - mean)).average().orElse(0);
                sum += mean == 0 ? 0 : Math.sqrt(var) / mean;
                directions++;
            }
            result.put(line.id(), directions == 0 ? 0 : sum / directions);
        }
        return result;
    }

    private static String trunkKey(String lineId, int direction) {
        return lineId + (direction > 0 ? "+" : "-");
    }

    /** Logs a departure from the last station of a branched line's trunk for {@link #trunkHeadwayCv()}. */
    private void recordTrunkDeparture(TrainState t, ServicePattern p) {
        String trunkEnd = trunkEnds.get(t.lineId);
        if (trunkEnd == null || !p.stationAtStop(t.stop).equals(trunkEnd)) {
            return;
        }
        Deque<Double> departures = trunkDepartures.computeIfAbsent(
                trunkKey(t.lineId, p.directionAtStop(t.stop)), k -> new ArrayDeque<>());
        departures.addLast(simTime);
        while (departures.size() > TRUNK_SAMPLE_DEPARTURES) {
            departures.removeFirst();
        }
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

    /** Recent short turns, oldest first (see {@link #MAX_SHORT_TURN_LOG}). */
    public List<ShortTurn> shortTurns() { return List.copyOf(shortTurnLog); }

    /** Short turns made at or after simulated time {@code since}, oldest first. */
    public List<ShortTurn> shortTurnsSince(double since) {
        return shortTurnLog.stream().filter(s -> s.simTime() >= since).toList();
    }

    /** Recent skipped stops, oldest first (see {@link #MAX_SKIPPED_STOP_LOG}). */
    public List<SkippedStop> skippedStops() { return List.copyOf(skippedStopLog); }

    /** Stops skipped at or after simulated time {@code since}, oldest first. */
    public List<SkippedStop> skippedStopsSince(double since) {
        return skippedStopLog.stream().filter(s -> s.simTime() >= since).toList();
    }

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
    public double stationCarriedPast(String stationId) { return stations.get(stationId).carriedPast; }
    public double stationLeftBehind(String stationId) { return stations.get(stationId).leftBehind; }
    public double stationHeldAtEntrance(String stationId) { return stations.get(stationId).heldAtEntrance; }
}
