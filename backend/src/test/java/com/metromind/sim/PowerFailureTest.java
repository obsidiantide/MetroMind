package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PowerFailureTest {

    private static final double START = 7 * 3600;

    private static SimulationEngine engine(com.metromind.network.MetroNetwork network) {
        SimulationEngine engine = new SimulationEngine(network, START, HeadwayStrategy.NO_CONTROL);
        engine.setRecordArrivals(true);
        return engine;
    }

    @Test
    void everyTrainOfTheLineStandsStillForTheOutage() {
        SimulationEngine engine = engine(TestNetworks.singleLine());
        engine.run(300, 1);
        Map<String, Integer> callsBefore = new java.util.HashMap<>();
        engine.trains().forEach(t -> callsBefore.put(t.id(), t.arrivals().size()));

        engine.cutPower("L1", 600);
        engine.run(590, 1);

        assertThat(engine.trains()).allSatisfy(t -> assertThat(t.arrivals()).hasSize(callsBefore.get(t.id())));
        assertThat(engine.powerFailures()).containsOnlyKeys("L1");
        assertThat(engine.snapshot().recentEvents()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(SimEvent.Type.INCIDENT);
            assertThat(e.subjectId()).isEqualTo("L1");
        });
    }

    @Test
    void trainsCarryOnWhenPowerIsRestoredAndRunLateByTheOutage() {
        SimulationEngine plain = engine(TestNetworks.singleTrain());
        SimulationEngine cut = engine(TestNetworks.singleTrain());
        plain.run(60, 1);
        cut.run(60, 1);
        cut.cutPower(cut.trains().get(0).lineId(), 300);
        plain.run(1200, 1);
        cut.run(1200, 1);

        assertThat(cut.powerFailures()).isEmpty();
        assertThat(cut.snapshot().recentEvents()).anySatisfy(e ->
                assertThat(e.type()).isEqualTo(SimEvent.Type.INCIDENT_CLEARED));
        List<Double> on = plain.trains().get(0).arrivals();
        List<Double> off = cut.trains().get(0).arrivals();
        // The first call after the cut comes exactly the outage later (the lone train has no headway to keep).
        assertThat(off.get(0)).isCloseTo(on.get(0) + 300, within(1.0));
        assertThat(cut.trains().get(0).delaySeconds()).isGreaterThan(200);
    }

    @Test
    void otherLinesKeepRunning() {
        SimulationEngine plain = engine(TestNetworks.twoLines());
        SimulationEngine cut = engine(TestNetworks.twoLines());
        String dark = cut.network().lines().get(0).id();
        String lit = cut.network().lines().get(1).id();
        cut.cutPower(dark, 600);
        plain.run(500, 1);
        cut.run(500, 1);

        // With no passengers able to change from the dark line in time, the lit line runs as it would have.
        assertThat(cut.trains().stream().filter(t -> t.lineId().equals(lit)).map(t -> t.arrivals().size()).toList())
                .isEqualTo(plain.trains().stream().filter(t -> t.lineId().equals(lit))
                        .map(t -> t.arrivals().size()).toList());
        assertThat(cut.trains().stream().filter(t -> t.lineId().equals(dark)))
                .allSatisfy(t -> assertThat(t.arrivals()).isEmpty());
    }

    @Test
    void aSecondFailureExtendsTheOutageAndCopiesKeepIt() {
        SimulationEngine engine = engine(TestNetworks.singleLine());
        engine.cutPower("L1", 300);
        engine.run(100, 1);
        engine.cutPower("L1", 400);
        assertThat(engine.powerFailures().get("L1")).isCloseTo(400, within(1e-9));

        SimulationEngine copy = engine.copy();
        assertThat(copy.powerFailures()).isEqualTo(engine.powerFailures());
        engine.run(399, 1);
        copy.run(399, 1);
        assertThat(engine.powerFailures()).containsOnlyKeys("L1");
        assertThat(copy.snapshot().trains()).isEqualTo(engine.snapshot().trains());
    }

    @Test
    void rejectsUnknownLinesAndEmptyOutages() {
        SimulationEngine engine = engine(TestNetworks.singleLine());
        assertThatThrownBy(() -> engine.cutPower("NOPE", 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.cutPower("L1", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
