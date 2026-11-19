package com.metromind.sim;

import com.metromind.network.FleetPlanPeriod;
import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DepotAccessRunsTest {

    /** The six-station test line with a fleet plan and a depot {@code accessSeconds} away from terminal A. */
    private static MetroNetwork planned(int trainCount, int spares, int accessSeconds, FleetPlanPeriod... plan) {
        MetroNetwork base = TestNetworks.singleLine();
        Line line = new Line("L1", "Line 1", "#f00", List.of("A", "B", "C", "D", "E", "F"),
                List.of(120, 120, 120, 120, 120), trainCount, 900, spares, List.of(), false, List.of(),
                List.of(), List.of(plan), null, accessSeconds);
        return new MetroNetwork("Test", base.stations(), List.of(line));
    }

    private static List<SimEvent> events(SimulationEngine engine, SimEvent.Type type) {
        return engine.snapshot().recentEvents().stream().filter(e -> e.type() == type).toList();
    }

    @Test
    void aTrainLeavingTheDepotRunsEmptyBeforeItEntersService() {
        // The peak starts at 07:30; with a 4-minute access run the extra train cannot be at A before 07:34.
        SimulationEngine engine = new SimulationEngine(planned(2, 0, 240, new FleetPlanPeriod("06:00", 2),
                new FleetPlanPeriod("07:30", 3)), 7 * 3600 + 29 * 60, HeadwayStrategy.NO_CONTROL);
        engine.run(3 * 60, 1);
        assertThat(engine.trains()).as("07:32: still on its way").hasSize(2);
        assertThat(engine.trainsOnDepotRuns("L1")).isEqualTo(1);
        assertThat(engine.depotTrains("L1")).as("it has left the depot").isZero();
        SimulationSnapshot.Metrics metrics = engine.snapshot().metrics();
        assertThat(metrics.trainsOnDepotRuns()).isEqualTo(1);
        assertThat(metrics.trainsInService()).isEqualTo(2);
        assertThat(metrics.trainsInDepot()).isZero();

        engine.run(5 * 60, 1);
        assertThat(engine.trains()).extracting(TrainState::id).containsExactly("L1-1", "L1-2", "L1-3");
        assertThat(engine.trainsOnDepotRuns("L1")).isZero();
        assertThat(engine.snapshot().metrics().trainsOnDepotRuns()).isZero();
        List<SimEvent> dispatched = events(engine, SimEvent.Type.TRAIN_DISPATCHED);
        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0).simTime()).isGreaterThanOrEqualTo(7 * 3600 + 34 * 60);
        assertThat(dispatched.get(0).message()).contains("as planned (3 trains)");
    }

    @Test
    void onlyOneTrainSetsOffForEachMissingTrain() {
        // While the first train is on its access run the line is still one short in service, but no second one
        // is sent for the same gap in the plan.
        SimulationEngine engine = new SimulationEngine(planned(2, 2, 600, new FleetPlanPeriod("06:00", 2),
                new FleetPlanPeriod("07:30", 3)), 7 * 3600 + 29 * 60, HeadwayStrategy.NO_CONTROL);
        engine.run(8 * 60, 1);
        assertThat(engine.trainsOnDepotRuns("L1")).isEqualTo(1);
        assertThat(engine.depotTrains("L1")).isEqualTo(2);
    }

    @Test
    void aWithdrawnTrainReachesTheDepotOnlyAfterItsAccessRun() {
        SimulationEngine engine = new SimulationEngine(planned(4, 0, 300, new FleetPlanPeriod("06:00", 4),
                new FleetPlanPeriod("09:30", 3)), 9 * 3600 + 25 * 60, HeadwayStrategy.BALANCED);
        while (events(engine, SimEvent.Type.TRAIN_WITHDRAWN).isEmpty()) {
            engine.run(10, 1);
        }
        assertThat(engine.trains()).hasSize(3);
        assertThat(engine.depotTrains("L1")).isZero();
        assertThat(engine.trainsOnDepotRuns("L1")).isEqualTo(1);

        engine.run(301, 1);
        assertThat(engine.depotTrains("L1")).isEqualTo(1);
        assertThat(engine.trainsOnDepotRuns("L1")).isZero();
    }

    @Test
    void adaptiveDispatchSendsOneSpareAtATimeDownALongAccessRun() {
        // Small trains overload the line at once; spares are reviewed every minute but the depot is ten away.
        MetroNetwork base = AdaptiveDispatchTest.crowdedLine(2, 3);
        Line l = base.line("L1");
        Line remote = new Line(l.id(), l.name(), l.color(), l.stationIds(), l.runSeconds(), l.trainCount(),
                l.trainCapacity(), l.spareTrains(), l.path(), l.ring(), l.timingStationIds(), l.branches(),
                l.fleetPlan(), null, 600);
        SimulationEngine engine = new SimulationEngine(new MetroNetwork("Test", base.stations(), List.of(remote)),
                8 * 3600, HeadwayStrategy.ADAPTIVE_DISPATCH);
        engine.run(9 * 60, 1);
        assertThat(engine.trainsOnDepotRuns("L1")).isEqualTo(1);
        assertThat(engine.depotTrains("L1")).isEqualTo(2);

        engine.run(6 * 60, 1);
        assertThat(engine.trains()).hasSize(3);
        assertThat(events(engine, SimEvent.Type.TRAIN_DISPATCHED)).extracting(SimEvent::subjectId)
                .containsExactly("L1-3");
        assertThat(engine.trainsOnDepotRuns("L1")).as("the next spare set off once L1-3 was in").isEqualTo(1);
    }

    @Test
    void withoutAnAccessTimeTrainsAppearOnThePlatformAtOnce() {
        SimulationEngine engine = new SimulationEngine(planned(2, 0, 0, new FleetPlanPeriod("06:00", 2),
                new FleetPlanPeriod("07:30", 3)), 7 * 3600 + 29 * 60, HeadwayStrategy.NO_CONTROL);
        engine.run(3 * 60, 1);
        assertThat(engine.trains()).hasSize(3);
        assertThat(engine.trainsOnDepotRuns("L1")).isZero();
    }

    @Test
    void copiesCarryTrainsOnTheirAccessRuns() {
        SimulationEngine live = new SimulationEngine(planned(2, 0, 240, new FleetPlanPeriod("06:00", 2),
                new FleetPlanPeriod("07:30", 3)), 7 * 3600 + 29 * 60, HeadwayStrategy.NO_CONTROL);
        live.setRecordArrivals(true);
        live.run(3 * 60, 1);
        SimulationEngine copy = live.copy();
        assertThat(copy.trainsOnDepotRuns("L1")).isEqualTo(1);

        live.run(20 * 60, 1);
        copy.run(20 * 60, 1);
        assertThat(copy.trains()).extracting(TrainState::arrivals)
                .isEqualTo(live.trains().stream().map(TrainState::arrivals).toList());
    }
}
