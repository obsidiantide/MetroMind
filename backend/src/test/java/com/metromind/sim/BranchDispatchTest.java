package com.metromind.sim;

import com.metromind.network.Branch;
import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Adaptive dispatching on a branched line sends spare trains where the overload is. */
class BranchDispatchTest {

    private static final double START = 8 * 3600;

    /** {@link TestNetworks#branched()} with small trains and two spares, everyone at A and B going to one place. */
    private static MetroNetwork crowded(String destination) {
        MetroNetwork base = TestNetworks.branched();
        Line line = new Line("L", "Line", "#c00", List.of("A", "B", "C", "D"), List.of(100, 110, 120), 6, 120, 2,
                List.of(), false, List.of(), List.of(new Branch("B", List.of("X", "Y"), List.of(90, 80), 1.0 / 3)));
        Map<String, Double> row = Map.of(destination, 1.0);
        return new MetroNetwork("Test", base.stations(), List.of(line), Map.of("A", row, "B", row));
    }

    private static List<String> dispatchedPatterns(String destination) {
        SimulationEngine engine = new SimulationEngine(crowded(destination), START, HeadwayStrategy.ADAPTIVE_DISPATCH);
        engine.run(1800, 1);
        List<String> dispatched = engine.snapshot().recentEvents().stream()
                .filter(e -> e.type() == SimEvent.Type.TRAIN_DISPATCHED).map(SimEvent::subjectId).toList();
        assertThat(dispatched).isNotEmpty();
        return dispatched.stream().map(id -> engine.train(id).patternId()).toList();
    }

    @Test
    void sparesRunToTheBranchWhenItsPassengersAreTheBacklog() {
        assertThat(dispatchedPatterns("Y")).containsOnly("L/Y");
    }

    @Test
    void sparesRunTheMainRouteWhenItsPassengersAreTheBacklog() {
        assertThat(dispatchedPatterns("D")).containsOnly("L");
    }

    @Test
    void theForecastOfAPatternOnlyCountsPassengersItsTrainsWouldTake() {
        SimulationEngine engine = new SimulationEngine(crowded("Y"), START, HeadwayStrategy.NO_CONTROL);
        engine.run(600, 1);
        Line line = engine.network().line("L");
        var patterns = line.patterns();
        assertThat(engine.patternForecast(line, patterns.get(1)))
                .isGreaterThan(engine.patternForecast(line, patterns.get(0)));
        assertThat(engine.patternForecast(line, patterns.get(1))).isLessThanOrEqualTo(engine.lineForecast(line));
    }
}
