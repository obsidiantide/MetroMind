package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.ServicePattern;
import com.metromind.network.Station;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-computed passenger routing over the whole network, including transfers between lines at interchanges.
 *
 * <p>Trains run on service patterns: one per line, or one per route of a branched line. Passengers follow the
 * route with the lowest expected travel time, where riding a segment costs its nominal run time plus one dwell,
 * waiting on a platform costs half the combined headway of the trains a passenger will board there, and changing
 * lines additionally costs {@link #TRANSFER_WALK_SECONDS}, unless the two lines meet at a cross-platform
 * interchange where passengers just step across the island. On a platform of a branched line a passenger boards
 * every pattern that takes them on equally well: anyone for the shared trunk takes the first train, while a
 * passenger for a branch waits for a train of that branch. Changing between the patterns of a branched line (from
 * a branch to the far end of the main route, say) counts as a change of platform. Costs are computed once per
 * destination with a deterministic Bellman–Ford relaxation, so every decision a passenger makes along the way is
 * consistent with the route they chose at the origin (a sub-path of a cheapest route is itself cheapest).
 *
 * <p>Immutable and shared between engine copies.
 */
final class RoutePlanner {

    /** Walking time between platforms when changing lines, on top of the wait for the next train. */
    static final double TRANSFER_WALK_SECONDS = 120;
    private static final double INF = Double.POSITIVE_INFINITY;
    private static final double EPS = 1e-6;

    private final List<Line> lines;
    private final double dwell;
    private final List<ServicePattern> patterns = new ArrayList<>();
    /** Line index of each pattern. */
    private final int[] patternLine;
    /** Pattern indices of each line, main pattern first. */
    private final int[][] linePatterns;
    private final Map<String, Integer> stationIndex = new HashMap<>();
    private final int stationCount;
    /** crossPlatform[from][to][station]: whether changing between the two lines there costs no walk. */
    private final boolean[][][] crossPlatform;
    /** Trains per second of each pattern passing any one of its stations in one direction. */
    private final double[] frequency;
    /** position[pattern][station]: index of the station in the pattern's station list, or -1. */
    private final int[][] position;
    /** cost[d][pattern][pos]: seconds to reach destination d from a platform of pattern at position pos. */
    private final double[][][] cost;
    /** entry[o][d]: queue a passenger entering at o for d joins; null if o == d or d is unreachable. */
    private final StationState.QueueKey[][] entry;
    /** stay[pattern][loopStop][d]: whether a passenger for d on a train arriving at that stop remains aboard. */
    private final boolean[][][] stay;
    /** transfer[pattern][loopStop][d]: queue joined by a passenger for d who alights there short of d. */
    private final StationState.QueueKey[][][] transfer;
    /** board[pattern][loopStop][d]: whether a passenger for d queueing there boards a train of the pattern. */
    private final boolean[][][] board;

    RoutePlanner(MetroNetwork network, double nominalDwell) {
        this.lines = network.lines();
        this.dwell = nominalDwell;
        List<Station> stations = network.stations();
        this.stationCount = stations.size();
        for (int i = 0; i < stations.size(); i++) {
            stationIndex.put(stations.get(i).id(), i);
        }
        linePatterns = new int[lines.size()][];
        List<Integer> owner = new ArrayList<>();
        for (int li = 0; li < lines.size(); li++) {
            List<ServicePattern> own = lines.get(li).patterns();
            linePatterns[li] = new int[own.size()];
            for (int k = 0; k < own.size(); k++) {
                linePatterns[li][k] = patterns.size();
                patterns.add(own.get(k));
                owner.add(li);
            }
        }
        patternLine = owner.stream().mapToInt(Integer::intValue).toArray();
        position = new int[patterns.size()][stationCount];
        frequency = new double[patterns.size()];
        for (int pi = 0; pi < patterns.size(); pi++) {
            ServicePattern p = patterns.get(pi);
            Arrays.fill(position[pi], -1);
            for (int pos = 0; pos < p.stationIds().size(); pos++) {
                position[pi][stationIndex.get(p.stationIds().get(pos))] = pos;
            }
            double loop = p.loopRunSeconds() + p.loopLength() * nominalDwell;
            frequency[pi] = p.trainCount() / loop;
        }
        crossPlatform = new boolean[lines.size()][lines.size()][stationCount];
        for (int li = 0; li < lines.size(); li++) {
            for (int lj = 0; lj < lines.size(); lj++) {
                for (int st = 0; st < stationCount; st++) {
                    crossPlatform[li][lj][st] = network.isCrossPlatform(lines.get(li).id(), lines.get(lj).id(),
                            stations.get(st).id());
                }
            }
        }
        cost = new double[stationCount][][];
        for (int d = 0; d < stationCount; d++) {
            cost[d] = costsTo(d, nominalDwell);
        }
        entry = new StationState.QueueKey[stationCount][stationCount];
        for (int o = 0; o < stationCount; o++) {
            for (int d = 0; d < stationCount; d++) {
                entry[o][d] = o == d ? null : bestQueue(o, d, -1, nominalDwell);
            }
        }
        stay = new boolean[patterns.size()][][];
        transfer = new StationState.QueueKey[patterns.size()][][];
        board = new boolean[patterns.size()][][];
        for (int pi = 0; pi < patterns.size(); pi++) {
            ServicePattern p = patterns.get(pi);
            int li = patternLine[pi];
            stay[pi] = new boolean[p.loopLength()][stationCount];
            transfer[pi] = new StationState.QueueKey[p.loopLength()][stationCount];
            board[pi] = new boolean[p.loopLength()][stationCount];
            for (int k = 0; k < p.loopLength(); k++) {
                int pos = p.positionAtStop(k);
                int s = stationIndex.get(p.stationAtStop(k));
                boolean terminal = p.isTerminalStop(k);
                int dir = p.directionAtStop(k);
                for (int d = 0; d < stationCount; d++) {
                    double ride = rideCost(d, pi, pos, dir, nominalDwell);
                    double best = bestRide(d, li, s, dir, nominalDwell);
                    board[pi][k][d] = best == INF || ride <= best + EPS;
                    if (d == s) {
                        continue;
                    }
                    stay[pi][k][d] = !terminal && ride < INF && ride <= cost[d][pi][pos] + EPS;
                    if (!stay[pi][k][d]) {
                        StationState.QueueKey q = bestQueue(s, d, li, nominalDwell);
                        transfer[pi][k][d] = q != null ? q : entry[s][d];
                    }
                }
            }
        }
    }

    private double[][] costsTo(int d, double dwell) {
        double[][] c = new double[patterns.size()][];
        for (int pi = 0; pi < patterns.size(); pi++) {
            c[pi] = new double[patterns.get(pi).stationIds().size()];
            Arrays.fill(c[pi], INF);
            int p = position[pi][d];
            if (p >= 0) {
                c[pi][p] = 0;
            }
        }
        cost[d] = c;
        // Bellman–Ford: every relaxation pass fixes at least one more node, so (nodes) passes suffice.
        int nodes = Arrays.stream(c).mapToInt(a -> a.length).sum();
        for (int pass = 0; pass <= nodes; pass++) {
            boolean changed = false;
            for (int pi = 0; pi < patterns.size(); pi++) {
                ServicePattern pattern = patterns.get(pi);
                int li = patternLine[pi];
                for (int p = 0; p < pattern.stationIds().size(); p++) {
                    if (c[pi][p] == 0) {
                        continue;
                    }
                    double best = Math.min(rideCost(d, pi, p, 1, dwell), rideCost(d, pi, p, -1, dwell));
                    int s = stationIndex.get(pattern.stationIds().get(p));
                    for (int lj = 0; lj < lines.size(); lj++) {
                        if (!mayChangeTo(li, lj)) {
                            continue;
                        }
                        for (int dir : new int[] {1, -1}) {
                            best = Math.min(best, walkSeconds(li, lj, s) + queueCost(d, lj, s, dir, dwell));
                        }
                    }
                    if (best < c[pi][p] - EPS) {
                        c[pi][p] = best;
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        return c;
    }

    /**
     * Whether a passenger alighting from a train of line {@code from} may queue for line {@code to}: any other
     * line, or another pattern of a branched line. On an unbranched line staying aboard is always at least as good.
     */
    private boolean mayChangeTo(int from, int to) {
        return from != to || linePatterns[from].length > 1;
    }

    /** Walk between the platforms of line {@code from} and line {@code to} at station {@code s}. */
    private double walkSeconds(int from, int to, int s) {
        return crossPlatform[from][to][s] ? 0 : TRANSFER_WALK_SECONDS;
    }

    /** Cost of riding pattern {@code pi} one segment from position {@code pos} in {@code dir}, then onwards to d. */
    private double rideCost(int d, int pi, int pos, int dir, double dwell) {
        if (pos < 0) {
            return INF;
        }
        ServicePattern pattern = patterns.get(pi);
        int next = pattern.nextPosition(pos, dir);
        if (next < 0) {
            return INF;
        }
        double run = pattern.runSeconds().get(dir > 0 ? pos : next);
        return run + dwell + cost[d][pi][next];
    }

    /** Cheapest ride to d on any pattern of line {@code li} leaving station {@code s} in {@code dir}. */
    private double bestRide(int d, int li, int s, int dir, double dwell) {
        double best = INF;
        for (int pi : linePatterns[li]) {
            best = Math.min(best, rideCost(d, pi, position[pi][s], dir, dwell));
        }
        return best;
    }

    /**
     * Expected cost to d of joining the platform queue of line {@code li} at station {@code s} for {@code dir}:
     * half the combined headway of the patterns that take the passenger there best, plus that ride.
     */
    private double queueCost(int d, int li, int s, int dir, double dwell) {
        double best = bestRide(d, li, s, dir, dwell);
        if (best == INF) {
            return INF;
        }
        double trainsPerSecond = 0;
        for (int pi : linePatterns[li]) {
            if (rideCost(d, pi, position[pi][s], dir, dwell) <= best + EPS) {
                trainsPerSecond += frequency[pi];
            }
        }
        return trainsPerSecond > 0 ? 1 / (2 * trainsPerSecond) + best : INF;
    }

    /**
     * Cheapest platform queue at station {@code s} for destination {@code d}. A passenger entering the station
     * passes {@code fromLine = -1}; one getting off line {@code fromLine} cannot rejoin it (unless it is branched)
     * and pays the walk to the other platform. Ties are broken by line order, then outbound before inbound.
     */
    private StationState.QueueKey bestQueue(int s, int d, int fromLine, double dwell) {
        StationState.QueueKey best = null;
        double bestCost = INF;
        for (int lj = 0; lj < lines.size(); lj++) {
            if (fromLine >= 0 && !mayChangeTo(fromLine, lj)) {
                continue;
            }
            double extra = fromLine < 0 ? 0 : walkSeconds(fromLine, lj, s);
            for (int dir : new int[] {1, -1}) {
                double c = extra + queueCost(d, lj, s, dir, dwell);
                if (c < bestCost - EPS) {
                    bestCost = c;
                    best = new StationState.QueueKey(lines.get(lj).id(), dir);
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ queries

    int stationCount() { return stationCount; }

    int index(String stationId) {
        Integer i = stationIndex.get(stationId);
        if (i == null) {
            throw new IllegalArgumentException("Unknown station " + stationId);
        }
        return i;
    }

    int lineIndex(String lineId) {
        for (int li = 0; li < lines.size(); li++) {
            if (lines.get(li).id().equals(lineId)) {
                return li;
            }
        }
        throw new IllegalArgumentException("Unknown line " + lineId);
    }

    int patternIndex(String patternId) {
        for (int pi = 0; pi < patterns.size(); pi++) {
            if (patterns.get(pi).id().equals(patternId)) {
                return pi;
            }
        }
        throw new IllegalArgumentException("Unknown service pattern " + patternId);
    }

    /** Whether trains of pattern {@code patternIdx} call at {@code station}. */
    boolean serves(int patternIdx, int station) { return position[patternIdx][station] >= 0; }

    /** Queue joined by a passenger entering at {@code origin} for {@code destination}; null if not reachable. */
    StationState.QueueKey entryQueue(int origin, int destination) { return entry[origin][destination]; }

    boolean reachable(int origin, int destination) { return entry[origin][destination] != null; }

    /** Per destination: does a passenger on a train of {@code patternIdx} arriving at {@code loopStop} stay aboard? */
    boolean[] staysAboard(int patternIdx, int loopStop) { return stay[patternIdx][loopStop]; }

    /**
     * Per destination: does a passenger queueing on the platform a train of {@code patternIdx} leaves from at
     * {@code loopStop} board it? Always true on an unbranched line.
     */
    boolean[] boards(int patternIdx, int loopStop) { return board[patternIdx][loopStop]; }

    /** Queue joined by a passenger who alights at {@code loopStop} short of {@code destination}. */
    StationState.QueueKey transferQueue(int patternIdx, int loopStop, int destination) {
        return transfer[patternIdx][loopStop][destination];
    }

    /** Expected door-to-door travel time from {@code origin} to {@code destination}, in seconds. */
    double travelSeconds(int origin, int destination) {
        if (origin == destination) {
            return 0;
        }
        double best = INF;
        for (int li = 0; li < lines.size(); li++) {
            for (int dir : new int[] {1, -1}) {
                best = Math.min(best, queueCost(destination, li, origin, dir, dwell));
            }
        }
        return best;
    }

    /** Lines a passenger rides from origin to destination, in order (for diagnostics and tests). */
    List<String> linesRidden(int origin, int destination) {
        List<String> ridden = new ArrayList<>();
        StationState.QueueKey q = entryQueue(origin, destination);
        int station = origin;
        int guard = 0;
        while (q != null && guard++ < 1000) {
            ridden.add(q.lineId());
            int pi = boardedPattern(q, station, destination);
            ServicePattern pattern = patterns.get(pi);
            int pos = position[pi][station];
            int k = q.direction() > 0 ? pos : pattern.loopLength() - pos;
            // Ride until the passenger alights.
            do {
                k = (k + 1) % pattern.loopLength();
            } while (stay[pi][k][destination]);
            station = stationIndex.get(pattern.stationAtStop(k));
            q = station == destination ? null : transfer[pi][k][destination];
        }
        return ridden;
    }

    /** First pattern (in line order) whose train a passenger for d in queue {@code q} at {@code station} boards. */
    private int boardedPattern(StationState.QueueKey q, int station, int d) {
        int li = lineIndex(q.lineId());
        for (int pi : linePatterns[li]) {
            int pos = position[pi][station];
            if (pos < 0) {
                continue;
            }
            ServicePattern pattern = patterns.get(pi);
            int k = q.direction() > 0 ? pos : pattern.loopLength() - pos;
            if (pattern.nextPosition(pos, q.direction()) >= 0 && board[pi][k % pattern.loopLength()][d]) {
                return pi;
            }
        }
        throw new IllegalStateException("No train of line " + q.lineId() + " serves this queue");
    }
}
