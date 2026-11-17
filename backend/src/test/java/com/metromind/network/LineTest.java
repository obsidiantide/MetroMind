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
}
