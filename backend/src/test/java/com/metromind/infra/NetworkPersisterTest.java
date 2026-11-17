package com.metromind.infra;

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
}
