package com.metromind.sim;

import com.metromind.network.Line;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationEngineTest {

    private static final double START = 8 * 3600;

    @Test
    void loopMapsStopsToStationsInBothDirections() {
        Line line = TestNetworks.singleLine().line("L1");
        assertThat(line.loopLength()).isEqualTo(10);
        assertThat(line.stationAtStop(0)).isEqualTo("A");
        assertThat(line.stationAtStop(5)).isEqualTo("F");
        assertThat(line.stationAtStop(6)).isEqualTo("E");
        assertThat(line.stationAtStop(9)).isEqualTo("B");
        assertThat(line.directionAtStop(4)).isEqualTo(1);
        assertThat(line.directionAtStop(5)).isEqualTo(-1);
    }

    @Test
    void trainsStartOnDistinctStopsAndMove() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        Set<Integer> stops = new HashSet<>();
        engine.trains().forEach(t -> stops.add(t.stop()));
        assertThat(stops).hasSize(4);

        List<Integer> before = engine.trains().stream().map(TrainState::stop).toList();
        engine.run(600, 1);
        List<Integer> after = engine.trains().stream().map(TrainState::stop).toList();
        assertThat(after).isNotEqualTo(before);
        assertThat(engine.snapshot().metrics().passengersServed()).isPositive();
    }

    @Test
    void neverTwoTrainsInTheSameBlock() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.injectBreakdown("L1-1", 900);
        for (int i = 0; i < 3600; i++) {
            engine.step(1);
            List<TrainState> running = engine.trains().stream().filter(t -> t.phase() == TrainPhase.RUNNING).toList();
            assertThat(running.stream().map(TrainState::stop).distinct().count()).isEqualTo(running.size());
        }
    }

    @Test
    void injectedDelayIsDetected() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.injectDelay("L1-2", 240);
        engine.run(900, 1);
        assertThat(engine.snapshot().recentEvents())
                .anyMatch(e -> e.type() == SimEvent.Type.DELAY_DETECTED && e.subjectId().equals("L1-2"));
    }

    @Test
    void copiesAreIndependentAndDeterministic() {
        SimulationEngine a = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.BALANCED);
        a.run(300, 1);
        SimulationEngine b = a.copy();
        a.run(600, 1);
        b.run(600, 1);
        assertThat(b.snapshot().trains()).isEqualTo(a.snapshot().trains());

        SimulationEngine c = a.copy();
        c.injectDelay("L1-1", 300);
        c.run(600, 1);
        a.run(600, 1);
        assertThat(c.snapshot().trains()).isNotEqualTo(a.snapshot().trains());
    }

    @Test
    void stationLoadPredictionFlagsOverload() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.injectBreakdown("L1-1", 3600);
        engine.run(2400, 1);
        double predicted = engine.predictWaiting("C", 900);
        assertThat(predicted).isGreaterThan(engine.stationWaiting("C") * 0.5);
        assertThat(SimulationEngine.riskLevel(500)).isEqualTo("HIGH");
        assertThat(SimulationEngine.riskLevel(250)).isEqualTo("MEDIUM");
        assertThat(SimulationEngine.riskLevel(10)).isEqualTo("LOW");
    }

    @Test
    void demandHasMorningAndEveningPeaks() {
        assertThat(DemandProfile.multiplier(8.25 * 3600)).isGreaterThan(DemandProfile.multiplier(12 * 3600));
        assertThat(DemandProfile.multiplier(18 * 3600)).isGreaterThan(DemandProfile.multiplier(14 * 3600));
        assertThat(DemandProfile.multiplier(3 * 3600)).isLessThan(0.5);
    }
}
