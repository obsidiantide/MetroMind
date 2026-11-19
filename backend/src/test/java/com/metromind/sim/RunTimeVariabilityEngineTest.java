package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunTimeVariabilityEngineTest {

    private static final double START = 7 * 3600;

    private static SimulationEngine engine(RunTimeVariability variability, HeadwayStrategy strategy) {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, strategy);
        engine.setRunTimeVariability(variability);
        engine.setRecordArrivals(true);
        return engine;
    }

    private static List<List<Double>> arrivals(SimulationEngine engine) {
        return engine.trains().stream().map(TrainState::arrivals).toList();
    }

    @Test
    void noVariabilityRunsExactlyAsBefore() {
        SimulationEngine plain = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        plain.setRecordArrivals(true);
        SimulationEngine none = engine(RunTimeVariability.NONE, HeadwayStrategy.NO_CONTROL);
        plain.run(3600, 1);
        none.run(3600, 1);
        assertThat(arrivals(none)).isEqualTo(arrivals(plain));
    }

    @Test
    void theSameSeedReproducesARunAndAnotherSeedChangesIt() {
        SimulationEngine a = engine(new RunTimeVariability(0.08, 1), HeadwayStrategy.NO_CONTROL);
        SimulationEngine b = engine(new RunTimeVariability(0.08, 1), HeadwayStrategy.NO_CONTROL);
        SimulationEngine c = engine(new RunTimeVariability(0.08, 2), HeadwayStrategy.NO_CONTROL);
        a.run(3600, 1);
        b.run(3600, 1);
        c.run(3600, 1);
        assertThat(arrivals(a)).isEqualTo(arrivals(b));
        assertThat(arrivals(a)).isNotEqualTo(arrivals(c));
        assertThat(a.snapshot().metrics()).isEqualTo(b.snapshot().metrics());
    }

    @Test
    void copiesDrawTheSameRunTimes() {
        SimulationEngine live = engine(new RunTimeVariability(0.08, 5), HeadwayStrategy.BALANCED);
        live.run(900, 1);
        SimulationEngine copy = live.copy();
        assertThat(copy.runTimeVariability()).isEqualTo(live.runTimeVariability());
        live.run(1800, 1);
        copy.run(1800, 1);
        assertThat(arrivals(copy)).isEqualTo(arrivals(live));
    }

    @Test
    void runsVaryAroundTheirNominalTime() {
        // The single test train calls at every stop; its runs of 120 s nominal now each take a little more or less.
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        engine.setRunTimeVariability(new RunTimeVariability(0.1, 3));
        engine.setRecordArrivals(true);
        engine.run(3600, 1);
        List<Double> times = engine.trains().get(0).arrivals();
        assertThat(times).hasSizeGreaterThan(10);

        SimulationEngine nominal = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        nominal.setRecordArrivals(true);
        nominal.run(3600, 1);
        assertThat(times.subList(0, 10)).isNotEqualTo(nominal.trains().get(0).arrivals().subList(0, 10));
    }

    @Test
    void irregularRunsBunchAnUncontrolledLine() {
        SimulationEngine steady = engine(RunTimeVariability.NONE, HeadwayStrategy.NO_CONTROL);
        SimulationEngine irregular = engine(new RunTimeVariability(0.15, 11), HeadwayStrategy.NO_CONTROL);
        steady.run(2 * 3600, 1);
        irregular.run(2 * 3600, 1);
        assertThat(irregular.headwayCv()).isGreaterThan(steady.headwayCv());
    }
}
