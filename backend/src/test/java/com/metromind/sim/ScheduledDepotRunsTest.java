package com.metromind.sim;

import com.metromind.network.Branch;
import com.metromind.network.FleetPlanPeriod;
import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledDepotRunsTest {

    private static MetroNetwork planned(int trainCount, int spares, FleetPlanPeriod... plan) {
        return planned(900, trainCount, spares, plan);
    }

    private static MetroNetwork planned(int capacity, int trainCount, int spares, FleetPlanPeriod... plan) {
        MetroNetwork base = TestNetworks.singleLine();
        Line line = new Line("L1", "Line 1", "#f00", List.of("A", "B", "C", "D", "E", "F"),
                List.of(120, 120, 120, 120, 120), trainCount, capacity, spares, List.of(), false, List.of(),
                List.of(), List.of(plan));
        return new MetroNetwork("Test", base.stations(), List.of(line));
    }

    private static long count(SimulationEngine engine, SimEvent.Type type) {
        return engine.snapshot().recentEvents().stream().filter(e -> e.type() == type).count();
    }

    @Test
    void theDepotHoldsTrainsForTheBusiestPeriodOnTopOfTheSpares() {
        SimulationEngine engine = new SimulationEngine(planned(2, 1, new FleetPlanPeriod("06:00", 2),
                new FleetPlanPeriod("07:30", 5)), 7 * 3600, HeadwayStrategy.NO_CONTROL);
        assertThat(engine.trains()).hasSize(2);
        assertThat(engine.depotTrains("L1")).isEqualTo(4);
    }

    @Test
    void trainsComeOutOfTheDepotWhenThePeakPeriodStarts() {
        SimulationEngine engine = new SimulationEngine(planned(2, 1, new FleetPlanPeriod("06:00", 2),
                new FleetPlanPeriod("07:30", 4)), 7 * 3600, HeadwayStrategy.NO_CONTROL);
        engine.run(25 * 60, 1);
        assertThat(engine.trains()).as("before 07:30").hasSize(2);

        engine.run(20 * 60, 1);
        assertThat(engine.trains()).extracting(TrainState::id).containsExactly("L1-1", "L1-2", "L1-3", "L1-4");
        assertThat(engine.depotTrains("L1")).as("the spare stays in the depot").isEqualTo(1);
        assertThat(engine.snapshot().recentEvents()).filteredOn(e -> e.type() == SimEvent.Type.TRAIN_DISPATCHED)
                .extracting(SimEvent::message)
                .allMatch(m -> m.contains("as planned (4 trains)"));
    }

    @Test
    void trainsReturnToTheDepotWhenTheOffPeakPeriodStarts() {
        SimulationEngine engine = new SimulationEngine(planned(4, 0, new FleetPlanPeriod("06:00", 4),
                new FleetPlanPeriod("09:30", 2)), 9 * 3600 + 20 * 60, HeadwayStrategy.BALANCED);
        engine.run(40 * 60, 1);
        assertThat(engine.trains()).hasSize(2);
        assertThat(engine.depotTrains("L1")).isEqualTo(2);
        assertThat(count(engine, SimEvent.Type.TRAIN_WITHDRAWN)).isEqualTo(2);
        // Passengers on the withdrawn trains were put back on the platform, not lost.
        assertThat(engine.snapshot().metrics().passengersDelivered()).isPositive();
    }

    @Test
    void adaptiveDispatchingKeepsSurplusTrainsWhileOverloadIsForecast() {
        // Small trains in the morning peak: the line stays overloaded after the plan drops to two trains.
        MetroNetwork network = planned(150, 4, 0, new FleetPlanPeriod("06:00", 4), new FleetPlanPeriod("08:00", 2));
        SimulationEngine adaptive = new SimulationEngine(network, 7.75 * 3600, HeadwayStrategy.ADAPTIVE_DISPATCH);
        SimulationEngine balanced = new SimulationEngine(network, 7.75 * 3600, HeadwayStrategy.BALANCED);
        adaptive.run(40 * 60, 1);
        balanced.run(40 * 60, 1);

        assertThat(adaptive.lineForecast(network.line("L1"))).isGreaterThanOrEqualTo(SimulationEngine.OVERLOAD_MEDIUM);
        assertThat(adaptive.trains()).hasSize(4);
        assertThat(count(adaptive, SimEvent.Type.TRAIN_WITHDRAWN)).isZero();
        assertThat(balanced.trains()).as("other strategies follow the plan").hasSize(2);
    }

    @Test
    void aLineWithoutAPlanKeepsItsFleet() {
        SimulationEngine engine = new SimulationEngine(planned(3, 1), 5 * 3600, HeadwayStrategy.NO_CONTROL);
        engine.run(3600, 1);
        assertThat(engine.trains()).hasSize(3);
        assertThat(engine.depotTrains("L1")).isEqualTo(1);
    }

    @Test
    void scheduledRunsAreDeterministic() {
        MetroNetwork network = planned(2, 0, new FleetPlanPeriod("06:00", 2), new FleetPlanPeriod("07:10", 4),
                new FleetPlanPeriod("07:50", 3));
        SimulationEngine a = new SimulationEngine(network, 7 * 3600, HeadwayStrategy.FORWARD_HOLDING);
        SimulationEngine b = new SimulationEngine(network, 7 * 3600, HeadwayStrategy.FORWARD_HOLDING);
        a.run(3600, 1);
        b.run(3600, 1);
        assertThat(a.trains()).extracting(TrainState::id).isEqualTo(b.trains().stream().map(TrainState::id).toList());
        assertThat(a.snapshot().metrics()).isEqualTo(b.snapshot().metrics());
    }

    @Test
    void aBranchedLineKeepsEachPatternsShareOfThePlannedFleet() {
        MetroNetwork base = TestNetworks.branched();
        Line template = base.line("L");
        Line line = new Line("L", "Line", "#c00", template.stationIds(), template.runSeconds(), 3, 900, 0,
                List.of(), false, List.of(), List.of(new Branch("B", List.of("X", "Y"), List.of(90, 80), 1.0 / 3)),
                List.of(new FleetPlanPeriod("06:00", 3), new FleetPlanPeriod("07:10", 6)));
        SimulationEngine engine = new SimulationEngine(new MetroNetwork("Test", base.stations(), List.of(line)),
                7 * 3600, HeadwayStrategy.BALANCED);
        engine.run(3600, 1);

        assertThat(engine.trains()).hasSize(6);
        assertThat(engine.trains()).filteredOn(t -> t.patternId.equals("L/Y")).hasSize(2);
        assertThat(engine.trains()).filteredOn(t -> t.patternId.equals("L")).hasSize(4);
    }
}
