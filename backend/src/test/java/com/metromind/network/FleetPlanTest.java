package com.metromind.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FleetPlanTest {

    private static final List<FleetPlanPeriod> PLAN = List.of(
            new FleetPlanPeriod("06:00", 3), new FleetPlanPeriod("07:00", 6), new FleetPlanPeriod("10:00", 4),
            new FleetPlanPeriod("23:00", 2));

    private static Line line(int trainCount, List<FleetPlanPeriod> plan, List<Branch> branches) {
        return new Line("L", "L", "#000", List.of("A", "B", "C"), List.of(90, 90), trainCount, 800, 1, List.of(),
                false, List.of(), branches, plan);
    }

    @Test
    void aLineWithoutAPlanRunsItsTrainCountAllDay() {
        Line plain = new Line("L", "L", "#000", List.of("A", "B"), List.of(90), 4, 800);
        assertThat(plain.fleetPlan()).isEmpty();
        assertThat(plain.plannedTrains(3 * 3600)).isEqualTo(4);
        assertThat(plain.plannedTrains(8 * 3600)).isEqualTo(4);
        assertThat(plain.peakPlannedTrains()).isEqualTo(4);
    }

    @Test
    void plannedTrainsFollowThePeriodInForce() {
        Line line = line(5, PLAN, List.of());
        assertThat(line.plannedTrains(6 * 3600)).isEqualTo(3);
        assertThat(line.plannedTrains(7 * 3600 - 1)).isEqualTo(3);
        assertThat(line.plannedTrains(7 * 3600)).isEqualTo(6);
        assertThat(line.plannedTrains(12 * 3600)).isEqualTo(4);
        assertThat(line.plannedTrains(23.5 * 3600)).isEqualTo(2);
    }

    @Test
    void theLastPeriodCarriesOnPastMidnight() {
        Line line = line(5, PLAN, List.of());
        assertThat(line.plannedTrains(2 * 3600)).isEqualTo(2);
        // The next day repeats the plan.
        assertThat(line.plannedTrains(24 * 3600 + 8 * 3600)).isEqualTo(6);
    }

    @Test
    void peakIsTheBusiestPeriodOrTheStartingFleet() {
        assertThat(line(5, PLAN, List.of()).peakPlannedTrains()).isEqualTo(6);
        assertThat(line(8, PLAN, List.of()).peakPlannedTrains()).isEqualTo(8);
    }

    @Test
    void periodsMustBeInOrder() {
        List<FleetPlanPeriod> unordered = List.of(new FleetPlanPeriod("07:00", 6), new FleetPlanPeriod("06:00", 3));
        assertThatThrownBy(() -> line(5, unordered, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in order");
    }

    @Test
    void periodsAreValidated() {
        assertThatThrownBy(() -> new FleetPlanPeriod("7:00", 3)).hasMessageContaining("HH:mm");
        assertThatThrownBy(() -> new FleetPlanPeriod("24:00", 3)).hasMessageContaining("time of day");
        assertThatThrownBy(() -> new FleetPlanPeriod("07:00", 0)).hasMessageContaining("at least one train");
        assertThat(new FleetPlanPeriod("07:30", 1).fromSeconds()).isEqualTo(7 * 3600 + 30 * 60);
    }

    @Test
    void everyServicePatternKeepsATrain() {
        Branch branch = new Branch("B", List.of("X"), List.of(60), 0.3);
        List<FleetPlanPeriod> tooFew = List.of(new FleetPlanPeriod("06:00", 1));
        assertThatThrownBy(() -> line(5, tooFew, List.of(branch)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one train per service pattern");
    }

    @Test
    void fleetPlanIsReadFromJson() throws Exception {
        Line line = new ObjectMapper().readValue("""
                {"id": "L", "name": "L", "color": "#000", "stationIds": ["A", "B"], "runSeconds": [90],
                 "trainCount": 4, "trainCapacity": 800,
                 "fleetPlan": [{"from": "05:30", "trains": 2}, {"from": "07:00", "trains": 5}]}
                """, Line.class);
        assertThat(line.fleetPlan()).containsExactly(new FleetPlanPeriod("05:30", 2), new FleetPlanPeriod("07:00", 5));
        assertThat(line.plannedTrains(8 * 3600)).isEqualTo(5);
    }
}
