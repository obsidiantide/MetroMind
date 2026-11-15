package com.metromind.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class InMemoryStationLocatorTest {

    private final MetroNetwork network = new MetroNetwork("Test", List.of(
            new Station("A", "Alpha", 40.000, 49.800, 10),
            new Station("B", "Bravo", 40.010, 49.800, 10),
            new Station("C", "Charlie", 40.000, 49.830, 10),
            // Same place as Bravo: ties are ordered by id.
            new Station("AB", "Bravo East Exit", 40.010, 49.800, 10)), List.of());
    private final InMemoryStationLocator locator = new InMemoryStationLocator(network);

    @Test
    void oneDegreeOfLatitudeIsAbout111Kilometres() {
        assertThat(InMemoryStationLocator.haversineMeters(40, 49.8, 41, 49.8)).isCloseTo(111_195, within(1.0));
        assertThat(InMemoryStationLocator.haversineMeters(40, 49.8, 40, 49.8)).isZero();
    }

    @Test
    void ordersStationsByDistance() {
        var nearest = locator.nearest(40.009, 49.800, 3);
        assertThat(nearest).extracting(d -> d.station().id()).containsExactly("AB", "B", "A");
        assertThat(nearest.get(0).distanceMeters()).isCloseTo(111.2, within(0.1));
    }

    @Test
    void findsStationsWithinARadius() {
        // Charlie is ~2.56 km east of Alpha, Bravo ~1.11 km north.
        assertThat(locator.within(40.0, 49.8, 1_200)).extracting(d -> d.station().id())
                .containsExactly("A", "AB", "B");
        assertThat(locator.within(40.0, 49.8, 3_000)).hasSize(4);
        assertThat(locator.within(40.0, 49.8, 0)).extracting(d -> d.station().id()).containsExactly("A");
    }

    @Test
    void rejectsPointsOutsideWgs84() {
        assertThatThrownBy(() -> locator.nearest(91, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> locator.within(0, -181, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> locator.nearest(Double.NaN, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
