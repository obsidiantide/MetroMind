package com.metromind.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class LineTrackTest {

    // A and B on a track that makes a symmetric detour north between them.
    private final MetroNetwork network = new MetroNetwork("Test", List.of(
            new Station("A", "Alpha", 40.00, 49.80, 5),
            new Station("B", "Bravo", 40.00, 49.82, 5)), List.of(
            new Line("L", "L", "#000", List.of("A", "B"), List.of(200), 2, 800, 0,
                    List.of(List.of(49.80, 40.00), List.of(49.81, 40.01), List.of(49.82, 40.00))),
            new Line("S", "S", "#000", List.of("A", "B"), List.of(200), 2, 800)));

    @Test
    void placesTrainsAlongTheCurveInBothDirections() {
        LineTrack track = LineTrack.of(network.line("L"), network);

        assertThat(track.position("A", "B", 0)).containsExactly(40.00, 49.80);
        assertThat(track.position("A", "B", 1)).containsExactly(40.00, 49.82);
        double[] half = track.position("A", "B", 0.5);
        assertThat(half[0]).as("halfway is the apex of the detour, not the chord").isCloseTo(40.01, within(1e-6));
        assertThat(half[1]).isCloseTo(49.81, within(1e-6));

        double[] quarterBack = track.position("B", "A", 0.25);
        assertThat(quarterBack[1]).isCloseTo(49.815, within(1e-6));
        assertThat(quarterBack[0]).isCloseTo(40.005, within(1e-6));
    }

    @Test
    void aBranchHasItsOwnTrackFromTheJunction() {
        List<Station> stations = List.of(new Station("A", "A", 40.00, 49.80, 5),
                new Station("B", "B", 40.00, 49.81, 5), new Station("C", "C", 40.00, 49.82, 5),
                new Station("X", "X", 40.02, 49.81, 5));
        Branch toX = new Branch("B", List.of("X"), List.of(150), 0.5,
                List.of(List.of(49.81, 40.00), List.of(49.82, 40.01), List.of(49.81, 40.02)));
        Line line = new Line("L", "L", "#000", List.of("A", "B", "C"), List.of(100, 100), 4, 800, 0,
                List.of(List.of(49.80, 40.00), List.of(49.82, 40.00)), false, List.of(), List.of(toX));
        MetroNetwork branched = new MetroNetwork("Test", stations, List.of(line));

        LineTrack main = LineTrack.of(line, branched);
        LineTrack branch = LineTrack.of(toX, branched);
        assertThat(main.covers("B", "X")).isFalse();
        assertThat(branch.covers("B", "X")).isTrue();
        assertThat(branch.covers("A", "B")).isFalse();
        double[] half = branch.position("B", "X", 0.5);
        assertThat(half[0]).as("halfway round the curve, east of the chord").isCloseTo(40.01, within(1e-6));
        assertThat(half[1]).isCloseTo(49.82, within(1e-6));

        assertThat(LineTrack.of(new Branch("B", List.of("X"), List.of(150), 0.5), branched)).isNull();
        assertThatThrownBy(() -> new Branch("B", List.of("X"), List.of(150), 0.5, List.of(List.of(49.8, 40.0))))
                .hasMessageContaining("[lon, lat]");
    }

    @Test
    void pointAtClampsToTheTrackEnds() {
        TrackGeometry g = new TrackGeometry(List.of(new double[]{49.80, 40.00}, new double[]{49.82, 40.00}));
        assertThat(g.pointAt(-10)).containsExactly(49.80, 40.00);
        assertThat(g.pointAt(1e9)).containsExactly(49.82, 40.00);
    }

    @Test
    void linesWithoutAnAlignmentHaveNoTrack() {
        assertThat(LineTrack.of(network.line("S"), network)).isNull();
        assertThatThrownBy(() -> LineTrack.of(network.line("L"), network).position("A", "Z", 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ringTrainsRunOverTheEndOfTheTrackBackToTheFirstStation() {
        // A square ring A (SW) → B (SE) → C (NE) → D (NW) → A, drawn closed.
        var stations = List.of(new Station("A", "A", 40.00, 49.80, 5), new Station("B", "B", 40.00, 49.82, 5),
                new Station("C", "C", 40.02, 49.82, 5), new Station("D", "D", 40.02, 49.80, 5));
        Line ring = new Line("K", "K", "#000", List.of("A", "B", "C", "D"), List.of(100, 100, 100, 100), 2, 800, 0,
                List.of(List.of(49.80, 40.00), List.of(49.82, 40.00), List.of(49.82, 40.02), List.of(49.80, 40.02),
                        List.of(49.80, 40.00)), true);
        LineTrack track = LineTrack.of(ring, new MetroNetwork("Test", stations, List.of(ring)));

        double[] half = track.position("D", "A", 0.5);
        assertThat(half[0]).as("halfway down the west side, not back round the ring").isCloseTo(40.01, within(1e-6));
        assertThat(half[1]).isCloseTo(49.80, within(1e-6));
        assertThat(track.position("D", "A", 1)).containsExactly(40.00, 49.80);
    }
}
