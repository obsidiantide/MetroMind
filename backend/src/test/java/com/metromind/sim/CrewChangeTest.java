package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CrewChangeTest {

    private static final double START = 7 * 3600;

    /** {@code base}'s only line with drivers swapping at {@code stationIds}, taking {@code seconds}. */
    private static MetroNetwork withCrewChanges(MetroNetwork base, List<String> stationIds, int seconds) {
        Line l = base.lines().get(0);
        Line relieved = new Line(l.id(), l.name(), l.color(), l.stationIds(), l.runSeconds(), l.trainCount(),
                l.trainCapacity(), l.spareTrains(), l.path(), l.ring(), l.timingStationIds(), l.branches(),
                l.fleetPlan(), l.runTimeVariability(), l.depotAccessSeconds(), l.dwellVariability(), stationIds, seconds);
        return new MetroNetwork(base.city(), base.stations(), List.of(relieved), base.odWeights());
    }

    private static SimulationEngine run(MetroNetwork network, double seconds) {
        SimulationEngine engine = new SimulationEngine(network, START, HeadwayStrategy.NO_CONTROL);
        engine.setRecordArrivals(true);
        engine.run(seconds, 1);
        return engine;
    }

    @Test
    void aCrewChangeLengthensTheCallWhereDriversSwap() {
        List<Double> plain = run(TestNetworks.singleTrain(), 600).trains().get(0).arrivals();
        List<Double> relieved = run(withCrewChanges(TestNetworks.singleTrain(), List.of("C"), 60), 600)
                .trains().get(0).arrivals();

        // The train calls at B, then C, where the driver swaps, then D: only the run on from C starts later.
        assertThat(relieved.subList(0, 2)).isEqualTo(plain.subList(0, 2));
        assertThat(relieved.get(2)).isCloseTo(plain.get(2) + 60, within(1e-9));
    }

    @Test
    void theTimetableAllowsForCrewChangesSoTrainsDoNotRunLate() {
        // Drivers swap at both terminals and in the middle: three long calls per trip would make every train
        // minutes late if the timetable did not plan them.
        SimulationEngine engine = run(withCrewChanges(TestNetworks.singleLine(), List.of("A", "C", "F"), 90), 3600);

        assertThat(engine.trains()).allSatisfy(t -> assertThat(t.delaySeconds()).isLessThan(30));
    }
}
