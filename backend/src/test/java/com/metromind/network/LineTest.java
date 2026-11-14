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
}
