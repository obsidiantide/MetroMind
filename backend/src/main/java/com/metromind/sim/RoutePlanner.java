package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-computed passenger routing over the whole network, including transfers between lines at interchanges.
 *
 * <p>Passengers follow the route with the lowest expected travel time, where riding a segment costs its nominal
 * run time plus one dwell, boarding a line costs half its nominal headway (the expected wait) and changing lines
 * additionally costs {@link #TRANSFER_WALK_SECONDS}. Costs are computed once per destination with a deterministic
 * Bellman–Ford relaxation, so every decision a passenger makes along the way is consistent with the route they
 * chose at the origin (a sub-path of a cheapest route is itself cheapest).
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
    private final Map<String, Integer> stationIndex = new HashMap<>();
    private final int stationCount;
    /** Expected wait for a train of each line: half its nominal headway. */
    private final double[] boardPenalty;
    /** cost[d][line][pos]: seconds to reach destination d from a platform of line at position pos. */
    private final double[][][] cost;
    /** entry[o][d]: queue a passenger entering at o for d joins; null if o == d or d is unreachable. */
    private final StationState.QueueKey[][] entry;
    /** stay[line][loopStop][d]: whether a passenger for d on a train arriving at that stop remains aboard. */
    private final boolean[][][] stay;
    /** transfer[line][loopStop][d]: queue joined by a passenger for d who alights there short of d. */
    private final StationState.QueueKey[][][] transfer;

    RoutePlanner(MetroNetwork network, double nominalDwell) {
        this.lines = network.lines();
        this.dwell = nominalDwell;
        List<Station> stations = network.stations();
        this.stationCount = stations.size();
        for (int i = 0; i < stations.size(); i++) {
            stationIndex.put(stations.get(i).id(), i);
        }
        boardPenalty = new double[lines.size()];
        for (int li = 0; li < lines.size(); li++) {
            Line line = lines.get(li);
            double loop = line.loopRunSeconds() + line.loopLength() * nominalDwell;
            boardPenalty[li] = loop / Math.max(1, line.trainCount()) / 2.0;
        }
        cost = new double[stationCount][][];
        for (int d = 0; d < stationCount; d++) {
            cost[d] = costsTo(d, nominalDwell);
        }
        entry = new StationState.QueueKey[stationCount][stationCount];
        for (int o = 0; o < stationCount; o++) {
            for (int d = 0; d < stationCount; d++) {
                entry[o][d] = o == d ? null : bestQueue(o, d, -1, nominalDwell, 0);
            }
        }
        stay = new boolean[lines.size()][][];
        transfer = new StationState.QueueKey[lines.size()][][];
        for (int li = 0; li < lines.size(); li++) {
            Line line = lines.get(li);
            stay[li] = new boolean[line.loopLength()][stationCount];
            transfer[li] = new StationState.QueueKey[line.loopLength()][stationCount];
            for (int k = 0; k < line.loopLength(); k++) {
                int pos = position(line, k);
                int s = stationIndex.get(line.stationAtStop(k));
                boolean terminal = pos == 0 || pos == line.stationIds().size() - 1;
                int dir = line.directionAtStop(k);
                for (int d = 0; d < stationCount; d++) {
                    if (d == s) {
                        continue;
                    }
                    double ride = rideCost(d, li, pos, dir, nominalDwell);
                    stay[li][k][d] = !terminal && ride < INF && ride <= cost[d][li][pos] + EPS;
                    if (!stay[li][k][d]) {
                        StationState.QueueKey q = bestQueue(s, d, li, nominalDwell, TRANSFER_WALK_SECONDS);
                        transfer[li][k][d] = q != null ? q : entry[s][d];
                    }
                }
            }
        }
    }

    private static int position(Line line, int loopStop) {
        int n = line.stationIds().size();
        return loopStop < n ? loopStop : line.loopLength() - loopStop;
    }

    private int positionOf(Line line, int station) {
        for (int p = 0; p < line.stationIds().size(); p++) {
            if (stationIndex.get(line.stationIds().get(p)) == station) {
                return p;
            }
        }
        return -1;
    }

    private double[][] costsTo(int d, double dwell) {
        double[][] c = new double[lines.size()][];
        for (int li = 0; li < lines.size(); li++) {
            c[li] = new double[lines.get(li).stationIds().size()];
            Arrays.fill(c[li], INF);
            int p = positionOf(lines.get(li), d);
            if (p >= 0) {
                c[li][p] = 0;
            }
        }
        cost[d] = c;
        // Bellman–Ford: every relaxation pass fixes at least one more node, so (nodes) passes suffice.
        int nodes = Arrays.stream(c).mapToInt(a -> a.length).sum();
        for (int pass = 0; pass <= nodes; pass++) {
            boolean changed = false;
            for (int li = 0; li < lines.size(); li++) {
                Line line = lines.get(li);
                for (int p = 0; p < line.stationIds().size(); p++) {
                    if (c[li][p] == 0) {
                        continue;
                    }
                    double best = Math.min(rideCost(d, li, p, 1, dwell), rideCost(d, li, p, -1, dwell));
                    int s = stationIndex.get(line.stationIds().get(p));
                    for (int lj = 0; lj < lines.size(); lj++) {
                        int q = lj == li ? -1 : positionOf(lines.get(lj), s);
                        if (q >= 0) {
                            double ride = Math.min(rideCost(d, lj, q, 1, dwell), rideCost(d, lj, q, -1, dwell));
                            best = Math.min(best, TRANSFER_WALK_SECONDS + boardPenalty[lj] + ride);
                        }
                    }
                    if (best < c[li][p] - EPS) {
                        c[li][p] = best;
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

    /** Cost of riding line {@code li} one segment from position {@code pos} in {@code dir}, then onwards to d. */
    private double rideCost(int d, int li, int pos, int dir, double dwell) {
        Line line = lines.get(li);
        int next = pos + dir;
        if (next < 0 || next >= line.stationIds().size()) {
            return INF;
        }
        double run = line.runSeconds().get(Math.min(pos, next));
        return run + dwell + cost[d][li][next];
    }

    /**
     * Cheapest platform queue at station {@code s} for destination {@code d}, ignoring line {@code excludeLine}.
     * Ties are broken by line order, then outbound before inbound.
     */
    private StationState.QueueKey bestQueue(int s, int d, int excludeLine, double dwell, double extra) {
        StationState.QueueKey best = null;
        double bestCost = INF;
        for (int lj = 0; lj < lines.size(); lj++) {
            int q = lj == excludeLine ? -1 : positionOf(lines.get(lj), s);
            if (q < 0) {
                continue;
            }
            for (int dir : new int[] {1, -1}) {
                double c = extra + boardPenalty[lj] + rideCost(d, lj, q, dir, dwell);
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

    /** Queue joined by a passenger entering at {@code origin} for {@code destination}; null if not reachable. */
    StationState.QueueKey entryQueue(int origin, int destination) { return entry[origin][destination]; }

    boolean reachable(int origin, int destination) { return entry[origin][destination] != null; }

    /** Per destination: does a passenger on a train of {@code lineIdx} arriving at {@code loopStop} stay aboard? */
    boolean[] staysAboard(int lineIdx, int loopStop) { return stay[lineIdx][loopStop]; }

    /** Queue joined by a passenger who alights at {@code loopStop} short of {@code destination}. */
    StationState.QueueKey transferQueue(int lineIdx, int loopStop, int destination) {
        return transfer[lineIdx][loopStop][destination];
    }

    /** Expected door-to-door travel time from {@code origin} to {@code destination}, in seconds. */
    double travelSeconds(int origin, int destination) {
        if (origin == destination) {
            return 0;
        }
        double best = INF;
        for (int li = 0; li < lines.size(); li++) {
            int p = positionOf(lines.get(li), origin);
            if (p >= 0) {
                best = Math.min(best, boardPenalty[li] + Math.min(rideCost(destination, li, p, 1, dwell),
                        rideCost(destination, li, p, -1, dwell)));
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
            int li = lineIndex(q.lineId());
            Line line = lines.get(li);
            int pos = positionOf(line, station);
            int k = q.direction() > 0 ? pos : line.loopLength() - pos;
            // Ride until the passenger alights.
            do {
                k = (k + 1) % line.loopLength();
            } while (stay[li][k][destination]);
            station = stationIndex.get(line.stationAtStop(k));
            q = station == destination ? null : transfer[li][k][destination];
        }
        return ridden;
    }
}
