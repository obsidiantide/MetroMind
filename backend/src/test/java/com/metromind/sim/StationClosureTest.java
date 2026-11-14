package com.metromind.sim;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class StationClosureTest {

    private static final double START = 8 * 3600;

    @Test
    void trainsRunThroughAClosedStationWithoutServingIt() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.run(600, 1);
        engine.closeStation("C", 1200);
        double servedBefore = engine.snapshot().metrics().passengersServed();
        double deliveredAtC = engine.stationDelivered("C");
        engine.run(1100, 1);

        assertThat(engine.stationClosed("C")).isTrue();
        assertThat(engine.stationWaiting("C")).isZero();
        assertThat(engine.stationDelivered("C")).isEqualTo(deliveredAtC);
        assertThat(engine.departures("C", 1800)).isEmpty();
        assertThat(engine.snapshot().metrics().passengersServed()).isGreaterThan(servedBefore);
    }

    @Test
    void passengersForAClosedStationGetOffAtTheNextOne() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        engine.run(240, 1); // the train has served A and B outbound and carries passengers for C
        assertThat(engine.trainPassengersTo("L1-1", "C")).isPositive();
        engine.closeStation("C", 3600);
        // Run until the train has left D outbound.
        for (int i = 0; i < 600 && engine.train("L1-1").stop() < 3; i++) {
            engine.step(1);
        }
        assertThat(engine.train("L1-1").stop()).isEqualTo(3);
        assertThat(engine.trainPassengersTo("L1-1", "C")).isZero();
        assertThat(engine.stationDelivered("D")).isPositive();
    }

    @Test
    void closingAStationMovesItsPassengersToTheNeighbours() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.run(300, 1);
        double atC = engine.stationWaiting("C") + engine.stationHeldAtEntrance("C");
        assertThat(atC).isPositive();
        double before = engine.stationHeldAtEntrance("B") + engine.stationHeldAtEntrance("D");

        engine.closeStation("C", 600);
        assertThat(engine.stationWaiting("C")).isZero();
        assertThat(engine.stationHeldAtEntrance("B")).isCloseTo(atC / 2, within(1e-9));
        assertThat(engine.stationHeldAtEntrance("D") + engine.stationHeldAtEntrance("B") - before)
                .isCloseTo(atC, within(1e-9));
    }

    @Test
    void newArrivalsAtAClosedStationUseItsNeighbours() {
        SimulationEngine open = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        SimulationEngine closed = open.copy();
        closed.closeStation("A", 600);
        open.run(60, 1);
        closed.run(60, 1);
        // A's only neighbour is B, which therefore receives A's demand on top of its own.
        assertThat(closed.stationWaiting("B")).isGreaterThan(open.stationWaiting("B"));
        assertThat(closed.stationWaiting("A")).isZero();
    }

    @Test
    void stationReopensAfterTheClosure() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.closeStation("D", 120);
        assertThat(engine.stationClosed("D")).isTrue();
        engine.run(121, 1);
        assertThat(engine.stationClosed("D")).isFalse();
        assertThat(engine.snapshot().recentEvents())
                .anyMatch(e -> e.type() == SimEvent.Type.INCIDENT_CLEARED && e.subjectId().equals("D"));
        engine.run(60, 1);
        assertThat(engine.stationWaiting("D")).isPositive();
    }

    @Test
    void closureIsDeterministicAndCopiesAreIndependent() {
        SimulationEngine base = new SimulationEngine(TestNetworks.twoLines(), START, HeadwayStrategy.BALANCED);
        SimulationEngine a = base.copy();
        SimulationEngine b = base.copy();
        a.closeStation("C", 900);
        b.closeStation("C", 900);
        base.run(900, 1);
        a.run(900, 1);
        b.run(900, 1);
        assertThat(a.snapshot().metrics()).isEqualTo(b.snapshot().metrics());
        assertThat(base.stationClosed("C")).isFalse();
        assertThat(a.snapshot().metrics()).isNotEqualTo(base.snapshot().metrics());
    }

    @Test
    void rejectsUnknownStationsAndBadDurations() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        assertThatThrownBy(() -> engine.closeStation("NOPE", 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.closeStation("A", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
