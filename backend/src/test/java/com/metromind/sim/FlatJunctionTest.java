package com.metromind.sim;

import com.metromind.network.ServicePattern;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flat junction at B on {@link TestNetworks#branched()}: trains leaving B for the branch (towards X) cross the
 * track main-route trains run on from C back into B.
 */
class FlatJunctionTest {

    private static final double START = 7 * 3600;
    private static final double CROSSING = 30;

    /** Seconds in which a train leaving for X and a train running from C into B were on the crossing together. */
    private static int conflictSeconds(SimulationEngine engine, int seconds) {
        Map<String, ServicePattern> patterns = new HashMap<>();
        engine.network().line("L").patterns().forEach(p -> patterns.put(p.id(), p));
        int conflicts = 0;
        for (int i = 0; i < seconds; i++) {
            engine.step(1);
            boolean diverging = false;
            boolean merging = false;
            for (TrainState t : engine.trains()) {
                if (t.phase() != TrainPhase.RUNNING) {
                    continue;
                }
                ServicePattern p = patterns.get(t.patternId());
                String from = p.stationAtStop(t.stop());
                String to = p.stationAtStop((t.stop() + 1) % p.loopLength());
                diverging |= from.equals("B") && to.equals("X") && t.segmentElapsed < CROSSING;
                merging |= from.equals("C") && to.equals("B") && t.segmentDuration - t.segmentElapsed < CROSSING;
            }
            if (diverging && merging) {
                conflicts++;
            }
        }
        return conflicts;
    }

    @Test
    void onAGradeSeparatedJunctionBothMovesRunAtOnce() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.branched(), START, HeadwayStrategy.NO_CONTROL);
        assertThat(conflictSeconds(engine, 7200)).isPositive();
    }

    @Test
    void onAFlatJunctionTheCrossingIsNeverSharedUnderAnyStrategy() {
        for (HeadwayStrategy strategy : HeadwayStrategy.all()) {
            SimulationEngine engine = new SimulationEngine(TestNetworks.branchedFlat(CROSSING), START, strategy);
            assertThat(conflictSeconds(engine, 7200)).as(strategy.name()).isZero();
        }
    }

    @Test
    void conflictsAtAFlatJunctionCostTime() {
        SimulationEngine separated = new SimulationEngine(TestNetworks.branched(), START, HeadwayStrategy.NO_CONTROL);
        SimulationEngine flat = new SimulationEngine(TestNetworks.branchedFlat(CROSSING), START,
                HeadwayStrategy.NO_CONTROL);
        separated.run(7200, 1);
        flat.run(7200, 1);
        assertThat(flat.passengerRideSeconds()).isGreaterThan(separated.passengerRideSeconds());
    }

    @Test
    void aCopyKeepsTheCrossingOccupied() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.branchedFlat(CROSSING), START,
                HeadwayStrategy.NO_CONTROL);
        engine.run(1800, 1);
        SimulationEngine copy = engine.copy();
        engine.run(1800, 1);
        copy.run(1800, 1);
        assertThat(copy.snapshot().trains()).isEqualTo(engine.snapshot().trains());
    }
}
