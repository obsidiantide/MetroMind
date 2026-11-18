package com.metromind.sim;

import com.metromind.network.ServicePattern;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Headway control on the trunk a branched line's service patterns share. */
class TrunkHeadwayTest {

    private static final double START = 7 * 3600;

    /** Coefficient of variation of the gaps between departures of any train of the line from B towards C or X. */
    private static double junctionDepartureCv(HeadwayStrategy strategy) {
        SimulationEngine engine = new SimulationEngine(TestNetworks.branched(), START, strategy);
        Map<String, ServicePattern> patterns = new HashMap<>();
        engine.network().line("L").patterns().forEach(p -> patterns.put(p.id(), p));
        List<Double> departures = new ArrayList<>();
        for (int i = 0; i < 7200; i++) {
            List<String> atJunction = new ArrayList<>();
            for (TrainState t : engine.trains()) {
                ServicePattern p = patterns.get(t.patternId());
                if (t.phase() != TrainPhase.RUNNING && p.stationAtStop(t.stop()).equals("B")
                        && p.directionAtStop(t.stop()) == 1) {
                    atJunction.add(t.id());
                }
            }
            engine.step(1);
            for (String id : atJunction) {
                if (engine.train(id).phase() == TrainPhase.RUNNING) {
                    departures.add(engine.simTime());
                }
            }
        }
        double[] gaps = new double[departures.size() - 1];
        for (int i = 1; i < departures.size(); i++) {
            gaps[i - 1] = departures.get(i) - departures.get(i - 1);
        }
        double mean = java.util.Arrays.stream(gaps).average().orElseThrow();
        double var = java.util.Arrays.stream(gaps).map(g -> (g - mean) * (g - mean)).average().orElseThrow();
        return Math.sqrt(var) / mean;
    }

    @Test
    void balancingEvensOutTheCombinedServiceOnTheTrunk() {
        // Regulating each pattern on its own left trains of the two patterns leaving the junction in pairs
        // (a CV of about 0.23 here, worse than no control at all); balancing the combined headway avoids that.
        double balanced = junctionDepartureCv(HeadwayStrategy.BALANCED);
        assertThat(balanced).isLessThan(0.12);
        assertThat(balanced).isLessThan(junctionDepartureCv(HeadwayStrategy.NO_CONTROL));
    }
}
