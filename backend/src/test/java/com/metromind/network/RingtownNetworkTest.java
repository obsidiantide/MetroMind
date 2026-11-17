package com.metromind.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.SimulationSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The bundled Ringtown network: two diameters crossing in the centre and a ring served in both directions. */
class RingtownNetworkTest {

    private static final double MORNING = 8 * 3600;

    private static MetroNetwork ringtown() throws Exception {
        return NetworkLoader.load(new ObjectMapper(), new ClassPathResource("network/ringtown.json"));
    }

    @Test
    void ringIsServedBothWaysOverTheSameStations() throws Exception {
        MetroNetwork n = ringtown();
        Line cw = n.line("RING-CW");
        Line ccw = n.line("RING-CCW");
        assertThat(cw.ring()).isTrue();
        assertThat(ccw.ring()).isTrue();
        assertThat(n.line("NS").ring()).isFalse();
        assertThat(ccw.stationIds()).containsExactlyInAnyOrderElementsOf(cw.stationIds());

        // Each anticlockwise hop takes as long as the clockwise hop over the same track.
        int size = cw.stationCount();
        for (int i = 0; i < size; i++) {
            String from = ccw.stationIds().get(i);
            String to = ccw.stationIds().get(ccw.sectionEnd(i));
            int cwSection = cw.stationIds().indexOf(to);
            assertThat(cw.stationIds().get(cw.sectionEnd(cwSection))).isEqualTo(from);
            assertThat(ccw.runSeconds().get(i)).as(from + "→" + to).isEqualTo(cw.runSeconds().get(cwSection));
        }
        // The diameters meet the ring at its four gates and each other in the centre.
        List<String> gates = new ArrayList<>(n.line("NS").stationIds());
        gates.addAll(n.line("EW").stationIds());
        gates.retainAll(cw.stationIds());
        assertThat(gates).containsExactlyInAnyOrder("K1", "K5", "K7", "K3");
    }

    @Test
    void theTwoRingDirectionsShareIslandPlatforms() throws Exception {
        MetroNetwork n = ringtown();
        for (String station : n.line("RING-CW").stationIds()) {
            assertThat(n.isCrossPlatform("RING-CW", "RING-CCW", station)).as(station).isTrue();
        }
        assertThat(n.isCrossPlatform("NS", "RING-CW", "K1")).isFalse();
    }

    @Test
    void simulatesDeterministicallyWithTransfersOntoTheRing() throws Exception {
        SimulationEngine a = new SimulationEngine(ringtown(), MORNING, HeadwayStrategy.BALANCED);
        SimulationEngine b = new SimulationEngine(ringtown(), MORNING, HeadwayStrategy.BALANCED);
        a.run(3600, 1);
        b.run(3600, 1);

        SimulationSnapshot.Metrics m = a.snapshot().metrics();
        assertThat(m.passengersDelivered()).isPositive();
        assertThat(a.stationTransfers("K1") + a.stationTransfers("K3") + a.stationTransfers("K5")
                + a.stationTransfers("K7")).isPositive();
        assertThat(m.headwayCoefficientOfVariation()).isLessThan(0.3);
        assertThat(b.snapshot().metrics()).isEqualTo(m);
    }
}
