package com.metromind.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TrackGeometryTest {

    // An L-shaped track: 0.01° north (~1112 m), then 0.01° east at latitude 40.01 (~852 m).
    private final TrackGeometry track = new TrackGeometry(List.of(
            new double[]{49.80, 40.00}, new double[]{49.80, 40.01}, new double[]{49.81, 40.01}));

    @Test
    void lengthFollowsTheBendsNotTheChord() {
        double north = InMemoryStationLocator.haversineMeters(40.00, 49.80, 40.01, 49.80);
        double east = InMemoryStationLocator.haversineMeters(40.01, 49.80, 40.01, 49.81);
        assertThat(track.lengthMeters()).isCloseTo(north + east, within(1e-6));
        assertThat(track.lengthMeters())
                .isGreaterThan(InMemoryStationLocator.haversineMeters(40.00, 49.80, 40.01, 49.81) + 300);
    }

    @Test
    void verticesProjectOntoThemselves() {
        assertThat(track.project(40.00, 49.80).chainageMeters()).isZero();
        assertThat(track.project(40.00, 49.80).offsetMeters()).isZero();
        assertThat(track.project(40.01, 49.81).chainageMeters()).isCloseTo(track.lengthMeters(), within(1e-6));
    }

    @Test
    void pointsBesideTheTrackProjectToTheFoot() {
        // ~85 m west of the first leg, halfway up it.
        TrackGeometry.Projection p = track.project(40.005, 49.799);
        assertThat(p.chainageMeters()).isCloseTo(556, within(1.0));
        assertThat(p.offsetMeters()).isCloseTo(85.2, within(0.5));

        // Halfway along the second leg, ~111 m south of it (inside the bend, closer to leg two than leg one).
        TrackGeometry.Projection q = track.project(40.009, 49.805);
        assertThat(q.chainageMeters()).isCloseTo(1112 + 426, within(2.0));
        assertThat(q.offsetMeters()).isCloseTo(111.2, within(0.5));
    }

    @Test
    void pointsPastTheEndsClampToTheTerminals() {
        assertThat(track.project(39.99, 49.80).chainageMeters()).isZero();
        assertThat(track.project(40.01, 49.82).chainageMeters()).isCloseTo(track.lengthMeters(), within(1e-6));
    }

    @Test
    void rejectsDegenerateOrInvalidTracks() {
        assertThatThrownBy(() -> new TrackGeometry(List.of(new double[]{49.8, 40.0})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackGeometry(List.of(new double[]{49.8, 40.0}, new double[]{49.8, 95.0})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[lon, lat]");
        assertThatThrownBy(() -> new TrackGeometry(List.of(new double[]{49.8, 40.0}, new double[]{Double.NaN, 40.0})))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
