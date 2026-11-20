package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CrewChangeRoutingTest {

    private static RoutePlanner planner(MetroNetwork network) {
        return new RoutePlanner(network, SimulationEngine.NOMINAL_DWELL);
    }

    /** {@code base} with drivers of line {@code lineId} swapping at {@code stationIds}, taking {@code seconds}. */
    static MetroNetwork withCrewChanges(MetroNetwork base, String lineId, List<String> stationIds, int seconds) {
        List<Line> lines = base.lines().stream().map(l -> !l.id().equals(lineId) ? l
                : new Line(l.id(), l.name(), l.color(), l.stationIds(), l.runSeconds(), l.trainCount(),
                        l.trainCapacity(), l.spareTrains(), l.path(), l.ring(), l.timingStationIds(), l.branches(),
                        l.fleetPlan(), l.runTimeVariability(), l.depotAccessSeconds(), l.dwellVariability(),
                        stationIds, seconds)).toList();
        return new MetroNetwork(base.city(), base.stations(), lines, base.odWeights(),
                base.crossPlatform());
    }

    /** Seconds from {@code from} to {@code to} beyond the trip from {@code from} to its neighbour B. */
    private static double beyondB(RoutePlanner p, String from, String to) {
        return p.travelSeconds(p.index(from), p.index(to)) - p.travelSeconds(p.index(from), p.index("B"));
    }

    @Test
    void ridingThroughARelievingStationCostsTheCrewChange() {
        RoutePlanner plain = planner(TestNetworks.singleLine());
        RoutePlanner relieved = planner(withCrewChanges(TestNetworks.singleLine(), "L1", List.of("C"), 60));

        assertThat(beyondB(relieved, "A", "D")).isCloseTo(beyondB(plain, "A", "D") + 60, within(1e-6));
    }

    @Test
    void alightingAtARelievingStationDoesNotWaitForTheSwap() {
        RoutePlanner plain = planner(TestNetworks.singleLine());
        RoutePlanner relieved = planner(withCrewChanges(TestNetworks.singleLine(), "L1", List.of("C"), 60));

        assertThat(beyondB(relieved, "A", "C")).isCloseTo(beyondB(plain, "A", "C"), within(1e-6));
    }

    /**
     * From C a passenger for Z changes at B to L2 or at D to L3; L2 is slightly faster, so it wins until its drivers
     * swap at B, where a four-minute crew change makes the change at D the better route.
     */
    @Test
    void passengersRouteAroundALongCrewChange() {
        MetroNetwork base = branchesAtBAndD();
        RoutePlanner plain = planner(base);
        RoutePlanner relieved = planner(withCrewChanges(base, "L2", List.of("B"), 240));

        assertThat(plain.linesRidden(plain.index("C"), plain.index("Z"))).containsExactly("L1", "L2");
        assertThat(relieved.linesRidden(relieved.index("C"), relieved.index("Z"))).containsExactly("L1", "L3");
    }

    @Test
    void theEngineMovesTransfersAwayFromALongCrewChange() {
        MetroNetwork base = branchesAtBAndD();
        SimulationEngine before = new SimulationEngine(base, 8 * 3600, HeadwayStrategy.NO_CONTROL);
        SimulationEngine after = new SimulationEngine(withCrewChanges(base, "L2", List.of("B"), 240), 8 * 3600,
                HeadwayStrategy.NO_CONTROL);
        before.run(1800, 1);
        after.run(1800, 1);

        assertThat(after.stationTransfers("D")).isGreaterThan(before.stationTransfers("D"));
        assertThat(after.stationTransfers("B")).isLessThan(before.stationTransfers("B"));
    }

    private static MetroNetwork branchesAtBAndD() {
        MetroNetwork base = TestNetworks.singleLine();
        List<Station> stations = new ArrayList<>(base.stations());
        stations.add(new Station("Z", "Z", 0.01, 0.02, 6));
        Line viaB = new Line("L2", "Line 2", "#0f0", List.of("B", "Z"), List.of(90), 2, 600);
        Line viaD = new Line("L3", "Line 3", "#00f", List.of("D", "Z"), List.of(100), 2, 600);
        return new MetroNetwork("Test", stations, List.of(base.line("L1"), viaB, viaD));
    }
}
