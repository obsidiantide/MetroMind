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
}
