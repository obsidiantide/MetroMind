package com.metromind.sim;

import com.metromind.network.MetroNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SectionalPowerFailureTest {

    private static final double START = 7 * 3600;

    private static SimulationEngine engine(MetroNetwork network) {
        SimulationEngine engine = new SimulationEngine(network, START, HeadwayStrategy.NO_CONTROL);
        engine.setRecordArrivals(true);
        return engine;
    }

    @Test
    void aTrainRunsUpToTheDeadStretchAndWaitsThereForPower() {
        SimulationEngine plain = engine(TestNetworks.singleTrain());
        SimulationEngine cut = engine(TestNetworks.singleTrain());
        cut.cutPower("L1", "D", "F", 1200);
        plain.run(700, 1);
        cut.run(700, 1);

        TrainState train = cut.trains().get(0);
        // B, C and D are live: the train calls there as usual, then cannot take current onto D–E.
        assertThat(train.arrivals()).hasSize(3);
        assertThat(plain.trains().get(0).arrivals().size()).isGreaterThan(3);
        assertThat(train.arrivals()).isEqualTo(plain.trains().get(0).arrivals().subList(0, 3));
        assertThat(train.phase()).isEqualTo(TrainPhase.WAITING_SIGNAL);
        assertThat(cut.sectionalPowerFailures()).containsExactly(List.of("L1", "D", "F"));
    }

    @Test
    void trainsInsideTheDeadStretchStandStillAndCarryOnWhenPowerIsBack() {
        SimulationEngine engine = engine(TestNetworks.singleTrain());
        engine.run(60, 1); // the train is running from A towards B
        assertThat(engine.trains().get(0).phase()).isEqualTo(TrainPhase.RUNNING);
        engine.cutPower("L1", "A", "D", 300);
        engine.run(290, 1);
        assertThat(engine.trains().get(0).arrivals()).isEmpty();

        engine.run(200, 1);
        assertThat(engine.sectionalPowerFailures()).isEmpty();
        assertThat(engine.trains().get(0).arrivals()).isNotEmpty();
        assertThat(engine.snapshot().recentEvents()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(SimEvent.Type.INCIDENT_CLEARED);
            assertThat(e.message()).contains("between");
        });
    }

    @Test
    void theRestOfTheLineKeepsRunning() {
        SimulationEngine plain = engine(TestNetworks.singleLine());
        SimulationEngine cut = engine(TestNetworks.singleLine());
        plain.run(300, 1);
        cut.run(300, 1);
        cut.cutPower("L1", "E", "F", 900);
        int before = cut.trains().stream().mapToInt(t -> t.arrivals().size()).sum();
        plain.run(600, 1);
        cut.run(600, 1);

        int during = cut.trains().stream().mapToInt(t -> t.arrivals().size()).sum() - before;
        int unaffected = plain.trains().stream().mapToInt(t -> t.arrivals().size()).sum() - before;
        assertThat(during).isPositive().isLessThan(unaffected);
        // A whole-line failure stops every train instead.
        SimulationEngine dark = engine(TestNetworks.singleLine());
        dark.run(300, 1);
        dark.cutPower("L1", 900);
        dark.run(600, 1);
        assertThat(dark.trains().stream().mapToInt(t -> t.arrivals().size()).sum()).isEqualTo(before);
    }

    @Test
    void theSameStretchExtendsTheOutageFromEitherEndAndCopiesKeepIt() {
        SimulationEngine engine = engine(TestNetworks.singleLine());
        engine.cutPower("L1", "B", "D", 300);
        engine.run(100, 1);
        engine.cutPower("L1", "D", "B", 400);
        assertThat(engine.sectionalPowerFailures()).containsExactly(List.of("L1", "B", "D"));

        SimulationEngine copy = engine.copy();
        engine.run(390, 1);
        copy.run(390, 1);
        assertThat(engine.sectionalPowerFailures()).hasSize(1);
        assertThat(copy.snapshot().trains()).isEqualTo(engine.snapshot().trains());
        engine.run(20, 1);
        assertThat(engine.sectionalPowerFailures()).isEmpty();
    }

    @Test
    void aStretchOnARingRunsRoundItsEnd() {
        SimulationEngine engine = engine(TestNetworks.ring());
        String line = engine.network().lines().get(0).id();
        List<String> stations = engine.network().line(line).stationIds();
        String last = stations.get(stations.size() - 1);
        engine.cutPower(line, last, stations.get(1), 600);
        assertThat(engine.sectionalPowerFailures()).containsExactly(List.of(line, last, stations.get(1)));
        engine.run(600, 1);
        assertThat(engine.sectionalPowerFailures()).isEmpty();
    }

    @Test
    void rejectsStationsOffTheLineAndEmptyOutages() {
        SimulationEngine engine = engine(TestNetworks.singleLine());
        assertThatThrownBy(() -> engine.cutPower("NOPE", "A", "B", 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.cutPower("L1", "A", "Z", 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.cutPower("L1", "B", "B", 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.cutPower("L1", "A", "B", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDeadFeederSectionIsListedAmongActiveIncidents() {
        SimulationEngine engine = engine(TestNetworks.singleLine());
        engine.run(60, 1);
        engine.cutPower("L1", "B", "D", 300);
        engine.run(100, 1);

        assertThat(engine.snapshot().activeIncidents()).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(SimulationSnapshot.IncidentKind.POWER_FAILURE);
            assertThat(i.subjectId()).isEqualTo("L1");
            assertThat(i.description()).contains("between");
            assertThat(i.remainingSeconds()).isCloseTo(200, org.assertj.core.api.Assertions.within(1.0));
        });
    }
}
