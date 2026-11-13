package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;

import java.util.List;

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
}
