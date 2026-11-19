package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DwellVariabilityEngineTest {

    private static final double START = 7 * 3600;

    private static SimulationEngine engine(DwellVariability variability, HeadwayStrategy strategy) {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, strategy);
        engine.setDwellVariability(variability);
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
        SimulationEngine none = engine(DwellVariability.NONE, HeadwayStrategy.NO_CONTROL);
        plain.run(3600, 1);
        none.run(3600, 1);
        assertThat(arrivals(none)).isEqualTo(arrivals(plain));
    }

    @Test
    void theSameSeedReproducesARunAndAnotherSeedChangesIt() {
        SimulationEngine a = engine(new DwellVariability(0.2, 1), HeadwayStrategy.NO_CONTROL);
        SimulationEngine b = engine(new DwellVariability(0.2, 1), HeadwayStrategy.NO_CONTROL);
        SimulationEngine c = engine(new DwellVariability(0.2, 2), HeadwayStrategy.NO_CONTROL);
        a.run(3600, 1);
        b.run(3600, 1);
        c.run(3600, 1);
        assertThat(arrivals(a)).isEqualTo(arrivals(b));
        assertThat(arrivals(a)).isNotEqualTo(arrivals(c));
        assertThat(a.snapshot().metrics()).isEqualTo(b.snapshot().metrics());
    }

    @Test
    void copiesDrawTheSameDwells() {
        SimulationEngine live = engine(new DwellVariability(0.2, 5), HeadwayStrategy.BALANCED);
        live.run(900, 1);
        SimulationEngine copy = live.copy();
        assertThat(copy.dwellVariability()).isEqualTo(live.dwellVariability());
        live.run(1800, 1);
        copy.run(1800, 1);
        assertThat(arrivals(copy)).isEqualTo(arrivals(live));
    }

    @Test
    void dwellsVaryButNeverCloseTheDoorsBeforeTheMinimum() {
        // With fixed run times every gap between two arrivals is the run time plus the dwell in between.
        SimulationEngine nominal = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        nominal.setRecordArrivals(true);
        SimulationEngine noisy = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        noisy.setDwellVariability(new DwellVariability(0.25, 3));
        noisy.setRecordArrivals(true);
        nominal.run(3600, 1);
        noisy.run(3600, 1);

        List<Double> plain = gaps(nominal.trains().get(0).arrivals());
        List<Double> varied = gaps(noisy.trains().get(0).arrivals());
        assertThat(varied).hasSizeGreaterThan(10);
        assertThat(varied.subList(0, 10)).isNotEqualTo(plain.subList(0, 10));
        // Every run takes its nominal 120 s, so a gap shorter than that plus the minimum dwell would mean a
        // low draw closed the doors early (one tick of slack for the 1 s step).
        assertThat(varied).allSatisfy(g -> assertThat(g)
                .isGreaterThanOrEqualTo(120 + SimulationEngine.MIN_DWELL - 1));
    }

    private static List<Double> gaps(List<Double> times) {
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) {
            gaps.add(times.get(i) - times.get(i - 1));
        }
        return gaps;
    }
}
