package com.metromind.sim;

import com.metromind.network.CrossPlatformInterchange;
import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CrossPlatformTransferTest {

    private static RoutePlanner planner(MetroNetwork network) {
        return new RoutePlanner(network, SimulationEngine.NOMINAL_DWELL);
    }

    private static MetroNetwork withCrossPlatform(MetroNetwork base, CrossPlatformInterchange... interchanges) {
        return new MetroNetwork(base.city(), base.stations(), base.lines(), base.odWeights(), List.of(interchanges));
    }

    @Test
    void aCrossPlatformChangeSavesTheWalk() {
        MetroNetwork walking = TestNetworks.twoLines();
        MetroNetwork stepping = withCrossPlatform(walking,
                new CrossPlatformInterchange(List.of("L1", "L2"), List.of("C")));

        RoutePlanner a = planner(walking);
        RoutePlanner b = planner(stepping);
        double walk = a.travelSeconds(a.index("A"), a.index("Y"));
        double step = b.travelSeconds(b.index("A"), b.index("Y"));

        assertThat(walk - step).isCloseTo(RoutePlanner.TRANSFER_WALK_SECONDS, within(1e-6));
        assertThat(b.linesRidden(b.index("A"), b.index("Y"))).containsExactly("L1", "L2");
    }

    @Test
    void anInterchangeAtOtherStationsStillCostsTheWalk() {
        MetroNetwork walking = TestNetworks.twoLines();
        MetroNetwork elsewhere = withCrossPlatform(walking,
                new CrossPlatformInterchange(List.of("L1", "L2"), List.of()));
        MetroNetwork otherLines = new MetroNetwork(walking.city(), walking.stations(), walking.lines(),
                Map.of(), List.of());

        RoutePlanner a = planner(walking);
        RoutePlanner b = planner(elsewhere);
        RoutePlanner c = planner(otherLines);

        // Listing no stations means every shared station, here C: the same saving as naming it.
        assertThat(a.travelSeconds(a.index("A"), a.index("Y")) - b.travelSeconds(b.index("A"), b.index("Y")))
                .isCloseTo(RoutePlanner.TRANSFER_WALK_SECONDS, within(1e-6));
        assertThat(c.travelSeconds(c.index("A"), c.index("Y")))
                .isEqualTo(a.travelSeconds(a.index("A"), a.index("Y")));
    }

    /**
     * From C a passenger for Z can change at B (to L2) or at D (to L3), both one stop away. L2 is slightly faster,
     * so it wins while both changes involve a walk; making D cross-platform tips the choice to L3.
     */
    @Test
    void passengersRouteViaTheCrossPlatformInterchange() {
        MetroNetwork walking = branchesAtBAndD();
        MetroNetwork stepping = withCrossPlatform(walking,
                new CrossPlatformInterchange(List.of("L1", "L3"), List.of("D")));

        RoutePlanner a = planner(walking);
        RoutePlanner b = planner(stepping);

        assertThat(a.linesRidden(a.index("C"), a.index("Z"))).containsExactly("L1", "L2");
        assertThat(b.linesRidden(b.index("C"), b.index("Z"))).containsExactly("L1", "L3");
    }

    @Test
    void theEngineMovesTransfersToTheCrossPlatformInterchange() {
        MetroNetwork walking = branchesAtBAndD();
        MetroNetwork stepping = withCrossPlatform(walking,
                new CrossPlatformInterchange(List.of("L1", "L3"), List.of("D")));

        SimulationEngine before = new SimulationEngine(walking, 8 * 3600, HeadwayStrategy.NO_CONTROL);
        SimulationEngine after = new SimulationEngine(stepping, 8 * 3600, HeadwayStrategy.NO_CONTROL);
        before.run(1800, 1);
        after.run(1800, 1);

        // Passengers from C for Z now change at D rather than B.
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
