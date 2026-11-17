package com.metromind.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossPlatformInterchangeTest {

    private static final List<Station> STATIONS = List.of(
            new Station("A", "A", 0, 0.00, 5), new Station("B", "B", 0, 0.01, 5),
            new Station("C", "C", 0, 0.02, 5), new Station("D", "D", 0.01, 0.01, 5));
    private static final Line L1 = new Line("L1", "One", "#f00", List.of("A", "B", "C"), List.of(90, 90), 2, 800);
    private static final Line L2 = new Line("L2", "Two", "#00f", List.of("D", "B", "C"), List.of(90, 90), 2, 800);

    @Test
    void coversOnlyTheListedStationsWhenGiven() {
        CrossPlatformInterchange x = new CrossPlatformInterchange(List.of("L1", "L2"), List.of("B"));

        assertThat(x.covers("L1", "L2", "B")).isTrue();
        assertThat(x.covers("L2", "L1", "B")).isTrue();
        assertThat(x.covers("L1", "L2", "C")).isFalse();
        assertThat(x.covers("L1", "L1", "B")).isFalse();
    }

    @Test
    void coversEveryCommonStationWhenNoneAreListed() {
        MetroNetwork n = new MetroNetwork("T", STATIONS, List.of(L1, L2), Map.of(),
                List.of(new CrossPlatformInterchange(List.of("L1", "L2"), null)));

        assertThat(n.isCrossPlatform("L1", "L2", "B")).isTrue();
        assertThat(n.isCrossPlatform("L2", "L1", "C")).isTrue();
    }

    @Test
    void networksWithoutTheListHaveNoCrossPlatformInterchanges() {
        MetroNetwork n = new MetroNetwork("T", STATIONS, List.of(L1, L2));

        assertThat(n.crossPlatform()).isEmpty();
        assertThat(n.isCrossPlatform("L1", "L2", "B")).isFalse();
    }

    @Test
    void needsTwoDifferentLines() {
        assertThatThrownBy(() -> new CrossPlatformInterchange(List.of("L1", "L1"), List.of()))
                .hasMessageContaining("at least two different lines");
    }

    @Test
    void rejectsUnknownLinesAndStationsNotServedByEveryLine() {
        assertThatThrownBy(() -> new MetroNetwork("T", STATIONS, List.of(L1, L2), Map.of(),
                List.of(new CrossPlatformInterchange(List.of("L1", "L9"), List.of()))))
                .hasMessageContaining("unknown line L9");
        assertThatThrownBy(() -> new MetroNetwork("T", STATIONS, List.of(L1, L2), Map.of(),
                List.of(new CrossPlatformInterchange(List.of("L1", "L2"), List.of("A")))))
                .hasMessageContaining("at A");
    }

    @Test
    void readsFromTheNativeFormat() throws Exception {
        String json = """
                {"city": "T",
                 "stations": [{"id": "A", "name": "A", "lat": 0, "lon": 0, "baseArrivalsPerMinute": 5},
                              {"id": "B", "name": "B", "lat": 0, "lon": 0.01, "baseArrivalsPerMinute": 5}],
                 "lines": [{"id": "L1", "name": "One", "color": "#f00", "stationIds": ["A", "B"],
                            "runSeconds": [90], "trainCount": 1, "trainCapacity": 800},
                           {"id": "L2", "name": "Two", "color": "#00f", "stationIds": ["B", "A"],
                            "runSeconds": [90], "trainCount": 1, "trainCapacity": 800}],
                 "crossPlatform": [{"lineIds": ["L1", "L2"], "stationIds": ["B"]}]}
                """;

        MetroNetwork n = new ObjectMapper().readValue(json, MetroNetwork.class);

        assertThat(n.isCrossPlatform("L1", "L2", "B")).isTrue();
        assertThat(n.isCrossPlatform("L1", "L2", "A")).isFalse();
    }
}
