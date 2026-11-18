package com.metromind.infra;

import com.metromind.network.Branch;
import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkPersisterTest {

    private final List<Station> stations = List.of(
            new Station("A", "Alpha", 40.00, 49.80, 5),
            new Station("B", "Bravo", 40.00, 49.82, 5));

    @Test
    void storesTheRealAlignmentWhenTheNetworkFileHasOne() {
        Line curved = new Line("L", "L", "#000", List.of("A", "B"), List.of(200), 2, 800, 0,
                List.of(List.of(49.80, 40.00), List.of(49.81, 40.004), List.of(49.82, 40.00)));
        var persister = new NetworkPersister(null, new MetroNetwork("Test", stations, List.of(curved)));

        assertThat(persister.trackWkt(curved)).isEqualTo("LINESTRING(49.8 40.0, 49.81 40.004, 49.82 40.0)");
    }

    @Test
    void fallsBackToStraightSegmentsBetweenStations() {
        Line straight = new Line("L", "L", "#000", List.of("A", "B"), List.of(200), 2, 800);
        var persister = new NetworkPersister(null, new MetroNetwork("Test", stations, List.of(straight)));

        assertThat(persister.trackWkt(straight)).isEqualTo("LINESTRING(49.8 40.0, 49.82 40.0)");
    }

    @Test
    void closesARingDrawnFromItsStations() {
        List<Station> triangle = List.of(stations.get(0), stations.get(1), new Station("C", "Charlie", 40.01, 49.81, 5));
        Line ring = new Line("K", "K", "#000", List.of("A", "B", "C"), List.of(200, 200, 200), 2, 800, 0, List.of(),
                true);
        var persister = new NetworkPersister(null, new MetroNetwork("Test", triangle, List.of(ring)));

        assertThat(persister.trackWkt(ring))
                .isEqualTo("LINESTRING(49.8 40.0, 49.82 40.0, 49.81 40.01, 49.8 40.0)");
    }

    @Test
    void storesABranchFromItsJunction() {
        List<Station> withBranch = List.of(stations.get(0), stations.get(1),
                new Station("C", "Charlie", 40.00, 49.84, 5), new Station("X", "X-ray", 40.02, 49.82, 5));
        Branch straight = new Branch("B", List.of("X"), List.of(150), 0.5);
        Branch curved = new Branch("B", List.of("X"), List.of(150), 0.5,
                List.of(List.of(49.82, 40.0), List.of(49.83, 40.01), List.of(49.82, 40.02)));
        Line line = new Line("L", "L", "#000", List.of("A", "B", "C"), List.of(200, 200), 4, 800, 0, List.of(),
                false, List.of(), List.of(straight));
        var persister = new NetworkPersister(null, new MetroNetwork("Test", withBranch, List.of(line)));

        assertThat(persister.trackWkt(straight)).isEqualTo("LINESTRING(49.82 40.0, 49.82 40.02)");
        assertThat(persister.trackWkt(curved)).isEqualTo("LINESTRING(49.82 40.0, 49.83 40.01, 49.82 40.02)");
    }
}
