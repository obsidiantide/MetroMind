package com.metromind.network;

import java.util.List;

/**
 * A line's track with its stations located on it, for placing trains between two stations along the real
 * alignment instead of the straight chord.
 */
public final class LineTrack {

    private final List<String> stationIds;
    private final TrackGeometry geometry;
    private final double[] stationChainage;
    private final boolean ring;

    private LineTrack(List<String> stationIds, List<List<Double>> path, boolean ring, MetroNetwork network) {
        this.stationIds = stationIds;
        this.ring = ring;
        this.geometry = new TrackGeometry(path.stream().map(p -> new double[]{p.get(0), p.get(1)}).toList());
        this.stationChainage = new double[stationIds.size()];
        for (int i = 0; i < stationIds.size(); i++) {
            Station s = network.station(stationIds.get(i));
            stationChainage[i] = geometry.project(s.lat(), s.lon()).chainageMeters();
        }
    }

    /** The line's track, or {@code null} when the network gives no alignment for it. */
    public static LineTrack of(Line line, MetroNetwork network) {
        return line.hasPath() ? new LineTrack(line.stationIds(), line.path(), line.ring(), network) : null;
    }

    /** A branch's track from its junction, or {@code null} when the network gives no alignment for it. */
    public static LineTrack of(Branch branch, MetroNetwork network) {
        return branch.hasPath() ? new LineTrack(branch.trackStationIds(), branch.path(), false, network) : null;
    }

    /**
     * Position {@code progress} (0..1) of the way from station {@code fromId} to station {@code toId} along the
     * track, as {@code [lat, lon]}. Works in either direction of travel; on a ring, whose trains only run the way
     * the track is drawn, the run from the last station back to the first continues over the track's end.
     */
    public double[] position(String fromId, String toId, double progress) {
        double from = stationChainage[index(fromId)];
        double to = stationChainage[index(toId)];
        double length = geometry.lengthMeters();
        if (ring && to < from) {
            to += length;
        }
        double at = from + (to - from) * progress;
        if (ring && at > length) {
            at -= length;
        }
        double[] lonLat = geometry.pointAt(at);
        return new double[]{lonLat[1], lonLat[0]};
    }

    /**
     * Whether both stations lie on this track, so {@link #position} can place a train between them. A line's track
     * only follows its main route; each branch has its own, starting at the junction.
     */
    public boolean covers(String fromId, String toId) {
        return stationIds.contains(fromId) && stationIds.contains(toId);
    }

    private int index(String stationId) {
        int i = stationIds.indexOf(stationId);
        if (i < 0) {
            throw new IllegalArgumentException("Station " + stationId + " is not on this line");
        }
        return i;
    }
}
