package com.metromind.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void platformCapacityIsOptionalInJson() throws Exception {
        Station unlimited = mapper.readValue("""
                {"id": "A", "name": "A", "lat": 0, "lon": 0, "baseArrivalsPerMinute": 5}
                """, Station.class);
        assertThat(unlimited.hasPlatformLimit()).isFalse();

        Station limited = mapper.readValue("""
                {"id": "B", "name": "B", "lat": 0, "lon": 0, "baseArrivalsPerMinute": 5, "platformCapacity": 600}
                """, Station.class);
        assertThat(limited.hasPlatformLimit()).isTrue();
        assertThat(limited.platformCapacity()).isEqualTo(600);
    }

    @Test
    void rejectsNonPositivePlatformCapacity() {
        assertThatThrownBy(() -> new Station("A", "A", 0, 0, 5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platformCapacity");
    }
}
