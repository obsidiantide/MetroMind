package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class DepartureBoardTest {

    private static final double START = 8 * 3600;

    @Test
    void trainsStartExactlyOnTheTimetable() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        List<Departure> board = engine.departures("C", 3600);

        // The loop takes 1450 s, so every train calls at C within the hour.
        assertThat(board).extracting(Departure::trainId)
                .containsExactlyInAnyOrder("L1-1", "L1-2", "L1-3", "L1-4");
        for (Departure d : board) {
            assertThat(d.delaySeconds()).isCloseTo(0.0, within(1e-6));
            assertThat(d.expectedTime()).isGreaterThanOrEqualTo(START);
        }
        assertThat(board).extracting(Departure::expectedTime).isSorted();
        assertThat(board).extracting(Departure::towardsStationId).contains("A", "F");
    }

    @Test
    void injectedDelayShowsOnTheBoard() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.injectDelay("L1-2", 240);
        Departure d = engine.departures("E", 3600).stream()
                .filter(x -> x.trainId().equals("L1-2")).findFirst().orElseThrow();
        assertThat(d.delaySeconds()).isCloseTo(240.0, within(1e-6));
        assertThat(d.plannedClock()).isEqualTo(SimulationEngine.clock(d.plannedTime()));
    }

    @Test
    void horizonLimitsTheBoard() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        List<Departure> soon = engine.departures("A", 300);
        assertThat(soon).allSatisfy(d -> assertThat(d.expectedTime() - START).isLessThanOrEqualTo(300));
        assertThat(soon.size()).isLessThan(engine.departures("A", 3600).size());
    }

    @Test
    void unknownStationIsRejected() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        assertThatThrownBy(() -> engine.departures("NOPE", 600)).isInstanceOf(IllegalArgumentException.class);
    }
}
