package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveDispatchTest {

    private static final double START = 8 * 3600;

    /** {@link TestNetworks#singleLine()} with small trains, so the morning peak overloads it, and spares. */
    static MetroNetwork crowdedLine(int trains, int spares) {
        MetroNetwork base = TestNetworks.singleLine();
        Line line = new Line("L1", "Line 1", "#f00", List.of("A", "B", "C", "D", "E", "F"),
                List.of(120, 120, 120, 120, 120), trains, 150, spares);
        return new MetroNetwork("Test", base.stations(), List.of(line));
    }

    @Test
    void sparesStayInTheDepotUnderOtherStrategies() {
        for (HeadwayStrategy s : List.of(HeadwayStrategy.NO_CONTROL, HeadwayStrategy.FORWARD_HOLDING,
                HeadwayStrategy.BALANCED)) {
            SimulationEngine engine = new SimulationEngine(crowdedLine(2, 2), START, s);
            engine.run(1800, 1);
            assertThat(engine.trains()).hasSize(2);
            assertThat(engine.depotTrains("L1")).isEqualTo(2);
        }
    }

    @Test
    void predictedOverloadBringsSparesIntoService() {
        SimulationEngine engine = new SimulationEngine(crowdedLine(2, 2), START, HeadwayStrategy.ADAPTIVE_DISPATCH);
        assertThat(engine.lineForecast(engine.network().line("L1")))
                .isGreaterThanOrEqualTo(SimulationEngine.OVERLOAD_HIGH);
        engine.run(1800, 1);

        assertThat(engine.trains()).extracting(TrainState::id).containsExactly("L1-1", "L1-2", "L1-3", "L1-4");
        assertThat(engine.depotTrains("L1")).isZero();
        assertThat(engine.snapshot().recentEvents()).filteredOn(e -> e.type() == SimEvent.Type.TRAIN_DISPATCHED)
                .extracting(SimEvent::subjectId).containsExactly("L1-3", "L1-4");
    }

    @Test
    void dispatchedTrainsCarryPassengers() {
        SimulationEngine adaptive = new SimulationEngine(crowdedLine(2, 2), START, HeadwayStrategy.ADAPTIVE_DISPATCH);
        SimulationEngine balanced = new SimulationEngine(crowdedLine(2, 2), START, HeadwayStrategy.BALANCED);
        adaptive.run(1800, 1);
        balanced.run(1800, 1);

        assertThat(adaptive.snapshot().metrics().passengersDelivered())
                .isGreaterThan(balanced.snapshot().metrics().passengersDelivered());
        assertThat(adaptive.snapshot().metrics().totalWaiting())
                .isLessThan(balanced.snapshot().metrics().totalWaiting());
    }

    @Test
    void anEmptyDepotDispatchesNothing() {
        SimulationEngine engine = new SimulationEngine(crowdedLine(2, 0), START, HeadwayStrategy.ADAPTIVE_DISPATCH);
        engine.run(1800, 1);
        assertThat(engine.trains()).hasSize(2);
    }

    @Test
    void quietLinesKeepTheirSparesInTheDepot() {
        // The default test line has big trains: no overload, so nothing to dispatch.
        MetroNetwork base = TestNetworks.singleLine();
        Line roomy = new Line("L1", "Line 1", "#f00", base.line("L1").stationIds(), base.line("L1").runSeconds(),
                4, 900, 2);
        SimulationEngine engine = new SimulationEngine(new MetroNetwork("Test", base.stations(), List.of(roomy)),
                START, HeadwayStrategy.ADAPTIVE_DISPATCH);
        engine.run(1800, 1);
        assertThat(engine.trains()).hasSize(4);
        assertThat(engine.depotTrains("L1")).isEqualTo(2);
    }

    @Test
    void dispatchingIsDeterministic() {
        SimulationEngine a = new SimulationEngine(crowdedLine(2, 2), START, HeadwayStrategy.ADAPTIVE_DISPATCH);
        SimulationEngine b = new SimulationEngine(crowdedLine(2, 2), START, HeadwayStrategy.ADAPTIVE_DISPATCH);
        a.run(600, 1);
        SimulationEngine fork = a.copy();
        a.run(1200, 1);
        b.run(1800, 1);
        fork.run(1200, 1);

        assertThat(fork.snapshot()).isEqualTo(a.snapshot());
        assertThat(b.snapshot()).isEqualTo(a.snapshot());
    }
}
