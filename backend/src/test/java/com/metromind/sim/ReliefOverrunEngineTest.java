package com.metromind.sim;

import com.metromind.network.MetroNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReliefOverrunEngineTest {

    private static final double START = 7 * 3600;

    private static MetroNetwork withCrewChanges(MetroNetwork base, List<String> stationIds) {
        return TestNetworks.withCrewChanges(base, stationIds, 60);
    }

    private static SimulationEngine engine(MetroNetwork network, ReliefOverrun overrun) {
        SimulationEngine engine = new SimulationEngine(network, START, HeadwayStrategy.NO_CONTROL);
        engine.setReliefOverrun(overrun);
        engine.setRecordArrivals(true);
        return engine;
    }

    private static List<List<Double>> arrivals(SimulationEngine engine) {
        return engine.trains().stream().map(t -> List.copyOf(t.arrivals())).toList();
    }

    @Test
    void noOverrunRunsExactlyAsBefore() {
        MetroNetwork network = withCrewChanges(TestNetworks.singleLine(), List.of("A", "F"));
        SimulationEngine plain = new SimulationEngine(network, START, HeadwayStrategy.NO_CONTROL);
        plain.setRecordArrivals(true);
        SimulationEngine none = engine(network, ReliefOverrun.NONE);
        plain.run(3600, 1);
        none.run(3600, 1);
        assertThat(arrivals(none)).isEqualTo(arrivals(plain));
        assertThat(none.reliefOverruns()).isZero();
    }

    @Test
    void lateReliefsOnlyLengthenCallsWhereDriversSwap() {
        // Every relief is late, but only at C: the calls at B and the run from B to C are untouched.
        MetroNetwork network = withCrewChanges(TestNetworks.singleTrain(), List.of("C"));
        SimulationEngine onTime = engine(network, ReliefOverrun.NONE);
        SimulationEngine late = engine(network, new ReliefOverrun(1, 60, 3));
        onTime.run(600, 1);
        late.run(600, 1);

        List<Double> planned = onTime.trains().get(0).arrivals();
        List<Double> overrun = late.trains().get(0).arrivals();
        assertThat(overrun.subList(0, 2)).isEqualTo(planned.subList(0, 2));
        assertThat(overrun.get(2)).isGreaterThan(planned.get(2));
        assertThat(late.reliefOverruns()).isEqualTo(1);
        assertThat(late.snapshot().metrics().reliefOverruns()).isEqualTo(1);
        assertThat(onTime.snapshot().metrics().reliefOverruns()).isZero();
        assertThat(late.snapshot().recentEvents()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(SimEvent.Type.RELIEF_OVERRUN);
            assertThat(e.subjectId()).isEqualTo(late.trains().get(0).id());
        });
    }

    @Test
    void theSameSeedReproducesARunAndCopiesShareTheDraws() {
        MetroNetwork network = withCrewChanges(TestNetworks.singleLine(), List.of("A", "C", "F"));
        SimulationEngine a = engine(network, new ReliefOverrun(0.3, 90, 1));
        SimulationEngine b = engine(network, new ReliefOverrun(0.3, 90, 1));
        SimulationEngine c = engine(network, new ReliefOverrun(0.3, 90, 2));
        a.run(1800, 1);
        b.run(1800, 1);
        c.run(1800, 1);
        assertThat(arrivals(a)).isEqualTo(arrivals(b));
        assertThat(arrivals(a)).isNotEqualTo(arrivals(c));

        SimulationEngine copy = a.copy();
        assertThat(copy.reliefOverrun()).isEqualTo(a.reliefOverrun());
        a.run(1800, 1);
        copy.run(1800, 1);
        assertThat(arrivals(copy)).isEqualTo(arrivals(a));
        assertThat(copy.reliefOverruns()).isEqualTo(a.reliefOverruns()).isPositive();
    }

    @Test
    void lateReliefsMakeTrainsLateAgainstTheTimetable() {
        MetroNetwork network = withCrewChanges(TestNetworks.singleLine(), List.of("A", "C", "F"));
        SimulationEngine onTime = engine(network, ReliefOverrun.NONE);
        SimulationEngine late = engine(network, new ReliefOverrun(0.5, 120, 4));
        onTime.run(3600, 1);
        late.run(3600, 1);

        double worstOnTime = onTime.trains().stream().mapToDouble(TrainState::delaySeconds).max().orElseThrow();
        double worstLate = late.trains().stream().mapToDouble(TrainState::delaySeconds).max().orElseThrow();
        assertThat(worstLate).isGreaterThan(worstOnTime + 30);
    }
}
