package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LineRunTimeVariabilityTest {

    private static final double START = 7 * 3600;

    /** {@link TestNetworks#twoLines()} with each line's own run-time spread ({@code null} for the engine-wide one). */
    private static MetroNetwork twoLines(Double l1, Double l2) {
        MetroNetwork base = TestNetworks.twoLines();
        List<Line> lines = base.lines().stream().map(l -> withSpread(l, l.id().equals("L1") ? l1 : l2)).toList();
        return new MetroNetwork(base.city(), base.stations(), lines, base.odWeights());
    }

    private static Line withSpread(Line l, Double spread) {
        return new Line(l.id(), l.name(), l.color(), l.stationIds(), l.runSeconds(), l.trainCount(), l.trainCapacity(),
                l.spareTrains(), l.path(), l.ring(), l.timingStationIds(), l.branches(), l.fleetPlan(), spread);
    }

    private static List<List<Double>> arrivals(SimulationEngine engine, String lineId) {
        return engine.trains().stream().filter(t -> t.lineId().equals(lineId)).map(TrainState::arrivals).toList();
    }

    private static SimulationEngine run(MetroNetwork network, RunTimeVariability variability) {
        SimulationEngine engine = new SimulationEngine(network, START, HeadwayStrategy.NO_CONTROL);
        engine.setRunTimeVariability(variability);
        engine.setRecordArrivals(true);
        engine.run(1800, 1);
        return engine;
    }

    @Test
    void aLinesOwnSpreadOverridesTheEngineWideOne() {
        SimulationEngine nominal = run(twoLines(null, null), RunTimeVariability.NONE);
        // Only L2 varies, once through its own spread and once through the engine's with L1 opting out: the same day.
        SimulationEngine ownSpread = run(twoLines(null, 0.15), new RunTimeVariability(0, 4));
        SimulationEngine optedOut = run(twoLines(0.0, null), new RunTimeVariability(0.15, 4));

        assertThat(arrivals(ownSpread, "L2")).isNotEqualTo(arrivals(nominal, "L2"));
        assertThat(arrivals(ownSpread, "L1")).isEqualTo(arrivals(optedOut, "L1"));
        assertThat(arrivals(ownSpread, "L2")).isEqualTo(arrivals(optedOut, "L2"));
        assertThat(ownSpread.snapshot().metrics()).isEqualTo(optedOut.snapshot().metrics());
    }

    @Test
    void aLineDeclaringNoSpreadRunsItsOwnTrainsToNominal() {
        // On a line of its own nothing else can move its trains, so with a spread of 0 it matches a nominal run.
        MetroNetwork base = TestNetworks.singleLine();
        MetroNetwork steady = new MetroNetwork(base.city(), base.stations(),
                List.of(withSpread(base.line("L1"), 0.0)), base.odWeights());
        assertThat(arrivals(run(steady, new RunTimeVariability(0.2, 4)), "L1"))
                .isEqualTo(arrivals(run(base, RunTimeVariability.NONE), "L1"));
    }

    @Test
    void aLinesOwnSpreadIsDrawnFromTheEngineSeed() {
        MetroNetwork network = twoLines(null, 0.15);
        assertThat(arrivals(run(network, new RunTimeVariability(0, 4)), "L2"))
                .isEqualTo(arrivals(run(network, new RunTimeVariability(0, 4)), "L2"))
                .isNotEqualTo(arrivals(run(network, new RunTimeVariability(0, 5)), "L2"));
    }

    @Test
    void withSigmaKeepsTheSeed() {
        RunTimeVariability v = new RunTimeVariability(0.05, 9);
        assertThat(v.withSigma(0.2)).isEqualTo(new RunTimeVariability(0.2, 9));
        assertThat(v.withSigma(0.05)).isSameAs(v);
    }
}
