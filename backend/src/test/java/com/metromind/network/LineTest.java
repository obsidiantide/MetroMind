package com.metromind.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void spareTrainsAreOptionalInJson() throws Exception {
        Line plain = mapper.readValue("""
                {"id": "L", "name": "L", "color": "#000", "stationIds": ["A", "B"], "runSeconds": [90],
                 "trainCount": 2, "trainCapacity": 800}
                """, Line.class);
        assertThat(plain.spareTrains()).isZero();

        Line withDepot = mapper.readValue("""
                {"id": "L", "name": "L", "color": "#000", "stationIds": ["A", "B"], "runSeconds": [90],
                 "trainCount": 2, "trainCapacity": 800, "spareTrains": 3}
                """, Line.class);
        assertThat(withDepot.spareTrains()).isEqualTo(3);
    }

    @Test
    void runTimeVariabilityIsOptionalInJson() throws Exception {
        Line plain = mapper.readValue("""
                {"id": "L", "name": "L", "color": "#000", "stationIds": ["A", "B"], "runSeconds": [90],
                 "trainCount": 2, "trainCapacity": 800}
                """, Line.class);
        assertThat(plain.runTimeVariability()).isNull();

        Line irregular = mapper.readValue("""
                {"id": "L", "name": "L", "color": "#000", "stationIds": ["A", "B"], "runSeconds": [90],
                 "trainCount": 2, "trainCapacity": 800, "runTimeVariability": 0.12}
                """, Line.class);
        assertThat(irregular.runTimeVariability()).isEqualTo(0.12);
    }

    @Test
    void rejectsARunTimeVariabilityOutsideTheSupportedRange() {
        for (double spread : new double[] {-0.01, 0.3, Double.NaN}) {
            assertThatThrownBy(() -> new Line("L", "L", "#000", List.of("A", "B"), List.of(90), 2, 800, 0, List.of(),
                    false, List.of(), List.of(), List.of(), spread))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("runTimeVariability");
        }
    }

    @Test
    void rejectsNegativeSpareTrains() {
        assertThatThrownBy(() -> new Line("L", "L", "#000", List.of("A", "B"), List.of(90), 2, 800, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spareTrains");
    }

    @Test
    void pathIsOptionalAndDefaultsToEmpty() throws Exception {
        Line line = mapper.readValue("""
                {"id": "L", "name": "L", "color": "#000", "stationIds": ["A", "B"], "runSeconds": [90],
                 "trainCount": 2, "trainCapacity": 800, "path": [[49.80, 40.00], [49.81, 40.005], [49.82, 40.00]]}
                """, Line.class);
        assertThat(line.hasPath()).isTrue();
        assertThat(line.path()).hasSize(3).first().isEqualTo(List.of(49.80, 40.00));

        assertThat(new Line("L", "L", "#000", List.of("A", "B"), List.of(90), 2, 800).hasPath()).isFalse();
    }

    @Test
    void rejectsMalformedPaths() {
        assertThatThrownBy(() -> new Line("L", "L", "#000", List.of("A", "B"), List.of(90), 2, 800, 0,
                List.of(List.of(49.8, 40.0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two points");
        assertThatThrownBy(() -> new Line("L", "L", "#000", List.of("A", "B"), List.of(90), 2, 800, 0,
                List.of(List.of(49.8, 40.0), List.of(49.9))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[lon, lat]");
    }

    @Test
    void linearLineReversesAtTerminals() {
        Line line = new Line("L", "L", "#000", List.of("A", "B", "C"), List.of(60, 90), 2, 800);
        assertThat(line.ring()).isFalse();
        assertThat(line.loopLength()).isEqualTo(4);
        assertThat(List.of(0, 1, 2, 3)).extracting(line::stationAtStop).containsExactly("A", "B", "C", "B");
        assertThat(List.of(0, 1, 2, 3)).extracting(line::directionAtStop).containsExactly(1, 1, -1, -1);
        assertThat(List.of(0, 1, 2, 3)).extracting(line::runSecondsFromStop).containsExactly(60, 90, 90, 60);
        assertThat(line.isTerminalStop(0)).isTrue();
        assertThat(line.isTerminalStop(2)).isTrue();
        assertThat(line.isTerminalStop(3)).isFalse();
        assertThat(line.nextPosition(2, 1)).isEqualTo(-1);
        assertThat(line.nextPosition(0, -1)).isEqualTo(-1);
        assertThat(line.sectionBetween(0, 2)).isEqualTo(-1);
        assertThat(line.towardsStationId(1, -1)).isEqualTo("A");
        assertThat(line.loopRunSeconds()).isEqualTo(300);
    }

    @Test
    void ringLineCirculatesWithoutTerminals() {
        Line ring = new Line("K", "Ring", "#000", List.of("A", "B", "C", "D"), List.of(60, 70, 80, 90), 2, 800, 0,
                List.of(), true);
        assertThat(ring.loopLength()).isEqualTo(4);
        assertThat(List.of(0, 1, 2, 3)).extracting(ring::stationAtStop).containsExactly("A", "B", "C", "D");
        assertThat(List.of(0, 1, 2, 3)).extracting(ring::directionAtStop).containsOnly(1);
        assertThat(List.of(0, 1, 2, 3)).extracting(ring::runSecondsFromStop).containsExactly(60, 70, 80, 90);
        assertThat(List.of(0, 1, 2, 3)).noneMatch(ring::isTerminalStop);
        assertThat(ring.nextPosition(3, 1)).isZero();
        assertThat(ring.nextPosition(1, -1)).isEqualTo(-1);
        assertThat(ring.sectionCount()).isEqualTo(4);
        assertThat(ring.sectionBetween(3, 0)).isEqualTo(3);
        assertThat(ring.sectionBetween(0, 3)).isEqualTo(3);
        assertThat(ring.sectionEnd(3)).isZero();
        assertThat(ring.towardsStationId(3, 1)).isEqualTo("A");
        assertThat(ring.loopRunSeconds()).isEqualTo(300);
    }

    @Test
    void ringIsOptionalInJson() throws Exception {
        Line ring = mapper.readValue("""
                {"id": "K", "name": "K", "color": "#000", "stationIds": ["A", "B", "C"], "runSeconds": [90, 90, 90],
                 "trainCount": 2, "trainCapacity": 800, "ring": true}
                """, Line.class);
        assertThat(ring.ring()).isTrue();
        assertThat(ring.loopLength()).isEqualTo(3);
    }

    @Test
    void rejectsMalformedRings() {
        assertThatThrownBy(() -> new Line("K", "K", "#000", List.of("A", "B"), List.of(90, 90), 2, 800, 0,
                List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("three stations");
        assertThatThrownBy(() -> new Line("K", "K", "#000", List.of("A", "B", "C"), List.of(90, 90), 2, 800, 0,
                List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closes the ring");
        assertThatThrownBy(() -> new Line("K", "K", "#000", List.of("A", "B", "A"), List.of(90, 90, 90), 2, 800,
                0, List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void timingPointsDefaultToTerminalsOrTheRingsFirstStation() {
        Line linear = new Line("L", "L", "#000", List.of("A", "B", "C"), List.of(90, 90), 2, 800);
        // Loop stops: 0 A, 1 B, 2 C, 3 B.
        assertThat(List.of(0, 1, 2, 3)).map(linear::isTimingStop).containsExactly(true, false, true, false);

        Line ring = new Line("R", "R", "#000", List.of("A", "B", "C"), List.of(90, 90, 90), 2, 800, 0,
                List.of(), true);
        assertThat(List.of(0, 1, 2)).map(ring::isTimingStop).containsExactly(true, false, false);
    }

    @Test
    void configuredTimingPointsReplaceTheDefaultInBothDirections() throws Exception {
        Line line = mapper.readValue("""
                {"id": "L", "name": "L", "color": "#000", "stationIds": ["A", "B", "C"], "runSeconds": [90, 90],
                 "trainCount": 2, "trainCapacity": 800, "timingStationIds": ["B"]}
                """, Line.class);

        assertThat(line.timingStationIds()).containsExactly("B");
        assertThat(List.of(0, 1, 2, 3)).map(line::isTimingStop).containsExactly(false, true, false, true);
    }

    @Test
    void rejectsTimingPointsOffTheLine() {
        assertThatThrownBy(() -> new Line("L", "L", "#000", List.of("A", "B"), List.of(90), 2, 800, 0,
                List.of(), false, List.of("Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timing point Z");
    }
}
