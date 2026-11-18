package com.metromind.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BranchTest {

    /** A–B–C–D with a branch from B to X–Y taking a third of six trains. */
    private static Line branched(List<String> timing) {
        return new Line("L", "L", "#000", List.of("A", "B", "C", "D"), List.of(100, 110, 120), 6, 800, 0,
                List.of(), false, timing, List.of(new Branch("B", List.of("X", "Y"), List.of(90, 80), 1.0 / 3)));
    }

    @Test
    void anUnbranchedLineHasItselfAsItsOnlyPattern() {
        Line line = new Line("L", "L", "#000", List.of("A", "B", "C"), List.of(100, 110), 4, 800);
        assertThat(line.patterns()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("L");
            assertThat(p.stationIds()).isEqualTo(line.stationIds());
            assertThat(p.runSeconds()).isEqualTo(line.runSeconds());
            assertThat(p.trainCount()).isEqualTo(4);
            assertThat(p.loopLength()).isEqualTo(line.loopLength());
        });
        assertThat(line.allStationIds()).containsExactly("A", "B", "C");
    }

    @Test
    void aBranchAddsAPatternThatFollowsTheTrunkToTheJunction() {
        Line line = branched(List.of());
        assertThat(line.allStationIds()).containsExactly("A", "B", "C", "D", "X", "Y");
        assertThat(line.patterns()).hasSize(2);

        ServicePattern main = line.patterns().get(0);
        assertThat(main.id()).isEqualTo("L");
        assertThat(main.stationIds()).containsExactly("A", "B", "C", "D");
        assertThat(main.trainCount()).isEqualTo(4);

        ServicePattern branch = line.patterns().get(1);
        assertThat(branch.id()).isEqualTo("L/Y");
        assertThat(branch.lineId()).isEqualTo("L");
        assertThat(branch.stationIds()).containsExactly("A", "B", "X", "Y");
        assertThat(branch.runSeconds()).containsExactly(100, 90, 80);
        assertThat(branch.trainCount()).isEqualTo(2);
        assertThat(branch.loopLength()).isEqualTo(6);
        assertThat(branch.stationAtStop(4)).isEqualTo("X");
        assertThat(branch.loopRunSeconds()).isEqualTo(2 * (100 + 90 + 80));
    }

    @Test
    void eachPatternKeepsTheTimingPointsOnIt() {
        Line line = branched(List.of("B", "X"));
        assertThat(line.patterns().get(0).timingStationIds()).containsExactly("B");
        assertThat(line.patterns().get(1).timingStationIds()).containsExactly("B", "X");
    }

    @Test
    void branchesAreOptionalInJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Line plain = mapper.readValue("""
                {"id": "L", "name": "L", "color": "#000", "stationIds": ["A", "B"], "runSeconds": [90],
                 "trainCount": 2, "trainCapacity": 800}
                """, Line.class);
        assertThat(plain.branches()).isEmpty();

        Line withBranch = mapper.readValue("""
                {"id": "L", "name": "L", "color": "#000", "stationIds": ["A", "B", "C"], "runSeconds": [90, 90],
                 "trainCount": 4, "trainCapacity": 800,
                 "branches": [{"junctionStationId": "B", "stationIds": ["X"], "runSeconds": [70], "share": 0.5}]}
                """, Line.class);
        assertThat(withBranch.patterns()).extracting(ServicePattern::id).containsExactly("L", "L/X");
    }

    @Test
    void rejectsMalformedBranches() {
        assertThatThrownBy(() -> new Branch("B", List.of("X", "Y"), List.of(90), 0.5))
                .hasMessageContaining("one entry per branch station");
        assertThatThrownBy(() -> new Branch("B", List.of("X"), List.of(90), 1.0))
                .hasMessageContaining("share");
        assertThatThrownBy(() -> new Branch("B", List.of(), List.of(), 0.5))
                .hasMessageContaining("at least one station");
    }

    @Test
    void rejectsBranchesThatDoNotFitTheLine() {
        assertThatThrownBy(() -> line(new Branch("A", List.of("X"), List.of(90), 0.5), false))
                .hasMessageContaining("intermediate station");
        assertThatThrownBy(() -> line(new Branch("Q", List.of("X"), List.of(90), 0.5), false))
                .hasMessageContaining("intermediate station");
        assertThatThrownBy(() -> line(new Branch("B", List.of("C"), List.of(90), 0.5), false))
                .hasMessageContaining("already on the line");
        assertThatThrownBy(() -> line(new Branch("B", List.of("X"), List.of(90), 0.05), false))
                .hasMessageContaining("gets no train");
        assertThatThrownBy(() -> line(new Branch("B", List.of("X"), List.of(90), 0.9), false))
                .hasMessageContaining("no train for the main route");
        assertThatThrownBy(() -> line(new Branch("B", List.of("X"), List.of(90), 0.5), true))
                .hasMessageContaining("cannot have branches");
    }

    @Test
    void networkChecksThatBranchStationsExist() {
        List<Station> stations = List.of(new Station("A", "A", 0, 0, 1), new Station("B", "B", 0, 0.01, 1),
                new Station("C", "C", 0, 0.02, 1), new Station("D", "D", 0, 0.03, 1));
        Line line = line(new Branch("B", List.of("X"), List.of(90), 0.25), false);
        assertThatThrownBy(() -> new MetroNetwork("T", stations, List.of(line)))
                .hasMessageContaining("unknown station X");
    }

    private static Line line(Branch branch, boolean ring) {
        List<Integer> runs = ring ? List.of(100, 100, 100, 100) : List.of(100, 100, 100);
        return new Line("L", "L", "#000", List.of("A", "B", "C", "D"), runs, 4, 800, 0, List.of(), ring,
                List.of(), List.of(branch));
    }
}
