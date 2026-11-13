package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class OriginDestinationTest {

    private static final double START = 8 * 3600;

    @Test
    void gravityModelWeighsDestinationsByRidershipAndSumsToOne() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.twoLines(), START, HeadwayStrategy.NO_CONTROL);
        // From B: A5 C12 D8 E6 F4 X6 Y10 = 51.
        assertThat(engine.odShare("B", "C")).isCloseTo(12.0 / 51, within(1e-12));
        assertThat(engine.odShare("B", "Y")).isCloseTo(10.0 / 51, within(1e-12));
        assertThat(engine.odShare("B", "B")).isZero();
        double total = engine.network().stations().stream().mapToDouble(s -> engine.odShare("B", s.id())).sum();
        assertThat(total).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void explicitOdWeightsOverrideGravityForThatOrigin() {
        MetroNetwork network = TestNetworks.twoLines(Map.of("A", Map.of("F", 3.0, "Y", 1.0)));
        SimulationEngine engine = new SimulationEngine(network, START, HeadwayStrategy.NO_CONTROL);
        assertThat(engine.odShare("A", "F")).isCloseTo(0.75, within(1e-12));
        assertThat(engine.odShare("A", "Y")).isCloseTo(0.25, within(1e-12));
        assertThat(engine.odShare("A", "C")).isZero();
        // Other origins keep the gravity model.
        assertThat(engine.odShare("B", "C")).isCloseTo(12.0 / 51, within(1e-12));

        engine.step(1);
        assertThat(engine.stationWaiting("A", "L1", 1, "C")).isZero();
        assertThat(engine.stationWaiting("A", "L1", 1, "F") / engine.stationWaiting("A"))
                .isCloseTo(0.75, within(1e-9));
    }

    @Test
    void invalidOdWeightsAreRejected() {
        assertThatThrownBy(() -> TestNetworks.twoLines(Map.of("Q", Map.of("A", 1.0))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Q");
        assertThatThrownBy(() -> TestNetworks.twoLines(Map.of("A", Map.of("B", -1.0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unreachableDestinationsGetNoDemand() {
        MetroNetwork base = TestNetworks.singleLine();
        List<Station> stations = new java.util.ArrayList<>(base.stations());
        stations.add(new Station("P", "P", 1, 1, 5));
        stations.add(new Station("Q", "Q", 1, 1.01, 5));
        Line island = new Line("L9", "Island", "#0f0", List.of("P", "Q"), List.of(100), 1, 500);
        SimulationEngine engine = new SimulationEngine(new MetroNetwork("Test", stations, List.of(base.line("L1"), island)),
                START, HeadwayStrategy.NO_CONTROL);
        assertThat(engine.odShare("A", "P")).isZero();
        assertThat(engine.odShare("P", "Q")).isEqualTo(1.0);
        assertThat(engine.route("A", "P")).isEmpty();
    }

    @Test
    void routesChangeLinesAtTheInterchangeOnlyWhenNeeded() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.twoLines(), START, HeadwayStrategy.NO_CONTROL);
        assertThat(engine.route("A", "D")).containsExactly("L1");
        assertThat(engine.route("A", "Y")).containsExactly("L1", "L2");
        assertThat(engine.route("Y", "F")).containsExactly("L2", "L1");
        assertThat(engine.route("X", "C")).containsExactly("L2");
        assertThat(engine.route("C", "C")).isEmpty();
    }

    @Test
    void passengersAlightAtTheirDestinationAndTransferAtTheInterchange() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.twoLines(), START, HeadwayStrategy.NO_CONTROL);
        engine.run(3600, 1);
        assertThat(engine.snapshot().metrics().passengersDelivered()).isPositive();
        assertThat(engine.stationTransfers("C")).isPositive();
        for (String other : List.of("A", "B", "D", "E", "F", "X", "Y")) {
            assertThat(engine.stationTransfers(other)).as("transfers at " + other).isZero();
        }
        assertThat(engine.snapshot().metrics().transfers()).isCloseTo(engine.stationTransfers("C"), within(1e-9));
        // Passengers bound for Y queue for L2 at C; nobody at C queues for a destination it already is.
        assertThat(engine.stationWaiting("C", "L2", 1, "Y") + engine.stationWaiting("C", "L2", -1, "Y"))
                .isGreaterThan(0);
        assertThat(engine.stationWaiting("C", "L2", -1, "Y")).isZero();
        for (TrainState t : engine.trains()) {
            assertThat(t.passengers()).isLessThanOrEqualTo(t.capacity() + 1e-9);
        }
    }

    @Test
    void nobodyStaysAboardPastTheirDestination() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        TrainState train = engine.train("L1-1");
        // Leaves A outbound; once it has called at C nobody for C (or B) can still be aboard.
        while (!(train.stop() == 2 && train.phase() == TrainPhase.DWELLING)) {
            engine.step(1);
        }
        assertThat(engine.trainPassengersTo("L1-1", "C")).isZero();
        assertThat(engine.trainPassengersTo("L1-1", "B")).isZero();
        assertThat(engine.trainPassengersTo("L1-1", "F")).isPositive();
        assertThat(engine.stationDelivered("C")).isPositive();
    }

    @Test
    void passengersAreConserved() {
        MetroNetwork network = TestNetworks.twoLines();
        SimulationEngine engine = new SimulationEngine(network, START, HeadwayStrategy.BALANCED);
        double perMinute = network.stations().stream().mapToDouble(Station::baseArrivalsPerMinute).sum();
        double generated = 0;
        for (int i = 0; i < 5400; i++) {
            engine.step(1);
            generated += perMinute * DemandProfile.multiplier(engine.simTime()) / 60.0;
        }
        SimulationSnapshot.Metrics m = engine.snapshot().metrics();
        double onBoard = engine.trains().stream().mapToDouble(TrainState::passengers).sum();
        assertThat(m.totalWaiting() + onBoard + m.passengersDelivered()).isCloseTo(generated, within(1e-6));
    }

    @Test
    void metropolisInterchangesAreUsed() {
        MetroNetwork metropolis = metropolis();
        SimulationEngine engine = new SimulationEngine(metropolis, START, HeadwayStrategy.NO_CONTROL);
        assertThat(engine.route("R1", "B6")).containsExactly("RED", "BLUE");
        assertThat(engine.route("B1", "R7")).hasSizeGreaterThan(1).startsWith("BLUE");
        engine.run(1800, 1);
        assertThat(engine.stationTransfers("R3") + engine.stationTransfers("R4") + engine.stationTransfers("B4"))
                .isPositive();
        assertThat(engine.stationTransfers("R1")).isZero();
    }

    private static MetroNetwork metropolis() {
        try (var in = OriginDestinationTest.class.getResourceAsStream("/network/metropolis.json")) {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(in, MetroNetwork.class);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
