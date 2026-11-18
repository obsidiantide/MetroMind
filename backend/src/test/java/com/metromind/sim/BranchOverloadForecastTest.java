package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The overload forecast on a branched line only counts trains that would take each waiting passenger. */
class BranchOverloadForecastTest {

    private static final double START = 8 * 3600;

    private static SimulationEngine engine(String destinationFromA) {
        SimulationEngine engine = new SimulationEngine(
                TestNetworks.branched(Map.of("A", Map.of(destinationFromA, 1.0))), START, HeadwayStrategy.NO_CONTROL);
        engine.run(600, 1);
        // Take both branch trains out of service for longer than the forecast horizon.
        engine.injectBreakdown("L-5", 3600);
        engine.injectBreakdown("L-6", 3600);
        engine.run(60, 1);
        return engine;
    }

    private static double waitingAt(SimulationEngine engine, String stationId) {
        return engine.snapshot().stations().stream().filter(s -> s.id().equals(stationId)).findFirst()
                .orElseThrow().waiting();
    }

    @Test
    void mainRouteTrainsDoNotRelievePassengersBoundForTheBranch() {
        SimulationEngine engine = engine("Y");
        assertThat(engine.trains()).filteredOn(t -> t.id().equals("L-5") || t.id().equals("L-6"))
                .allMatch(t -> t.patternId().equals("L/Y"));
        double queued = waitingAt(engine, "A");
        assertThat(queued).isPositive();
        // Main-route trains keep calling at A with room to spare, but nobody waiting there may board them.
        assertThat(engine.predictWaiting("A", 900)).isGreaterThan(queued);
    }

    @Test
    void mainRouteTrainsStillRelievePassengersForTheMainRoute() {
        SimulationEngine engine = engine("C");
        assertThat(engine.predictWaiting("A", 900)).isLessThan(engine("Y").predictWaiting("A", 900));
    }
}
