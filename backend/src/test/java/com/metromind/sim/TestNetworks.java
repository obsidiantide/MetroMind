package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;

import java.util.List;
import java.util.Map;

public final class TestNetworks {
    private TestNetworks() {
    }

    /** One straight line of 6 stations, 120 s apart, 4 trains. */
    public static MetroNetwork singleLine() {
        List<Station> stations = List.of(
                new Station("A", "A", 0, 0.00, 5), new Station("B", "B", 0, 0.01, 8),
                new Station("C", "C", 0, 0.02, 12), new Station("D", "D", 0, 0.03, 8),
                new Station("E", "E", 0, 0.04, 6), new Station("F", "F", 0, 0.05, 4));
        Line line = new Line("L1", "Line 1", "#f00", List.of("A", "B", "C", "D", "E", "F"),
                List.of(120, 120, 120, 120, 120), 4, 900);
        return new MetroNetwork("Test", stations, List.of(line));
    }

    /** Same six stations as {@link #singleLine()} with a single train, so queues are easy to follow. */
    public static MetroNetwork singleTrain() {
        MetroNetwork base = singleLine();
        Line line = new Line("L1", "Line 1", "#f00", List.of("A", "B", "C", "D", "E", "F"),
                List.of(120, 120, 120, 120, 120), 1, 900);
        return new MetroNetwork("Test", base.stations(), List.of(line));
    }

    /**
     * {@link #singleLine()} with a quiet stop at D: few passengers start there, and since the gravity model sends
     * trips in proportion to ridership, few travel to it either. A late train may skip such a stop.
     */
    public static MetroNetwork singleLineWithQuietStop() {
        MetroNetwork base = singleLine();
        List<Station> stations = base.stations().stream()
                .map(s -> s.id().equals("D") ? new Station("D", "D", s.lat(), s.lon(), 0.5) : s).toList();
        return new MetroNetwork("Test", stations, base.lines());
    }

    /**
     * {@link #singleLine()} crossed at C by a second line X–C–Y (2 trains), so trips such as A→Y need a transfer.
     */
    public static MetroNetwork twoLines() {
        return twoLines(Map.of());
    }

    public static MetroNetwork twoLines(Map<String, Map<String, Double>> odWeights) {
        MetroNetwork base = singleLine();
        List<Station> stations = new java.util.ArrayList<>(base.stations());
        stations.add(new Station("X", "X", 0.01, 0.02, 6));
        stations.add(new Station("Y", "Y", -0.01, 0.02, 10));
        Line cross = new Line("L2", "Line 2", "#00f", List.of("X", "C", "Y"), List.of(150, 150), 2, 600);
        return new MetroNetwork("Test", stations, List.of(base.line("L1"), cross), odWeights);
    }

    /**
     * The six stations of {@link #singleLine()} as a ring A→B→…→F→A, 120 s apart, run clockwise by 3 trains. A trip
     * F→A is one stop; A→F goes all the way round.
     */
    public static MetroNetwork ring() {
        Line ring = new Line("K", "Ring", "#a52", List.of("A", "B", "C", "D", "E", "F"),
                List.of(120, 120, 120, 120, 120, 120), 3, 900, 0, List.of(), true);
        return new MetroNetwork("Test", singleLine().stations(), List.of(ring));
    }
}
