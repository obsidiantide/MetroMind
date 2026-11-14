package com.metromind.sim;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SectionClosureTest {

    private static final double START = 8 * 3600;

    @Test
    void trainWaitsAtThePlatformBeforeAClosedSection() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        // The single train starts dwelling at A (stop 0); close A–B so it cannot leave.
        engine.closeSection("L1", "B", "A", 600);
        engine.run(300, 1);

        TrainState t = engine.train("L1-1");
        assertThat(t.stop()).isZero();
        assertThat(t.phase()).isEqualTo(TrainPhase.WAITING_SIGNAL);
        assertThat(engine.snapshot().recentEvents()).anyMatch(e -> e.type() == SimEvent.Type.INCIDENT
                && e.message().contains("A–B"));
    }

    @Test
    void closureCoversBothDirections() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        var line = engine.network().line("L1");
        engine.closeSection("L1", "C", "D", 60);
        assertThat(engine.sectionClosed(line, 2)).isTrue();  // C -> D outbound
        assertThat(engine.sectionClosed(line, 7)).isTrue();  // D -> C inbound
        assertThat(engine.sectionClosed(line, 3)).isFalse(); // D -> E
        assertThat(engine.sectionClosed(line, 6)).isFalse(); // E -> D
    }

    @Test
    void trainsResumeOnceTheSectionReopens() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        engine.closeSection("L1", "A", "B", 300);
        engine.run(301, 1);

        assertThat(engine.snapshot().recentEvents()).anyMatch(e -> e.type() == SimEvent.Type.INCIDENT_CLEARED);
        engine.run(5, 1);
        assertThat(engine.train("L1-1").phase()).isEqualTo(TrainPhase.RUNNING);
        assertThat(engine.train("L1-1").stop()).isZero();
    }

    @Test
    void closureDelaysFollowersAndIsReproducible() {
        SimulationEngine base = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        SimulationEngine a = base.copy();
        SimulationEngine b = base.copy();
        a.closeSection("L1", "C", "D", 900);
        b.closeSection("L1", "C", "D", 900);
        base.run(1200, 1);
        a.run(1200, 1);
        b.run(1200, 1);

        assertThat(a.snapshot().metrics().maxDelaySeconds())
                .isGreaterThan(base.snapshot().metrics().maxDelaySeconds() + 300);
        assertThat(a.snapshot().metrics()).isEqualTo(b.snapshot().metrics());
    }

    @Test
    void copiesKeepTheirOwnClosures() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        SimulationEngine copy = engine.copy();
        copy.closeSection("L1", "A", "B", 600);
        engine.run(120, 1);
        copy.run(120, 1);
        assertThat(engine.train("L1-1").phase()).isNotEqualTo(TrainPhase.WAITING_SIGNAL);
        assertThat(copy.train("L1-1").phase()).isEqualTo(TrainPhase.WAITING_SIGNAL);
    }

    @Test
    void rejectsStationsThatAreNotNeighbours() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        assertThatThrownBy(() -> engine.closeSection("L1", "A", "C", 60))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not adjacent");
        assertThatThrownBy(() -> engine.closeSection("NOPE", "A", "B", 60))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown line");
    }

    @Test
    void turnBackReversesTheTrainAndUnloadsItsPassengers() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        engine.closeSection("L1", "C", "D", 3600, ClosureResponse.TURN_BACK);
        // Run until the train has reached C outbound and reversed.
        for (int i = 0; i < 900 && !(engine.train("L1-1").stop() == 8); i++) {
            engine.step(1);
        }
        TrainState t = engine.train("L1-1");
        assertThat(t.stop()).as("C, inbound").isEqualTo(8);
        // Passengers for D, E and F were put back on C's outbound platform; nobody aboard is heading that way.
        assertThat(engine.trainPassengersTo("L1-1", "D")).isZero();
        assertThat(engine.trainPassengersTo("L1-1", "F")).isZero();
        assertThat(engine.stationWaiting("C", "L1", 1, "F")).isPositive();

        // The train shuttles A–C and never enters the closed section.
        for (int i = 0; i < 1800; i++) {
            engine.step(1);
            int stop = engine.train("L1-1").stop();
            assertThat(stop).isNotIn(3, 4, 5, 6, 7);
        }
        assertThat(engine.stationDelivered("A")).isPositive();
    }

    @Test
    void turnBackKeepsServiceRunningWhereWaitingDoesNot() {
        SimulationEngine base = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        SimulationEngine wait = base.copy();
        SimulationEngine turn = base.copy();
        wait.closeSection("L1", "D", "E", 1800, ClosureResponse.WAIT);
        turn.closeSection("L1", "D", "E", 1800, ClosureResponse.TURN_BACK);
        wait.run(1500, 1);
        turn.run(1500, 1);

        double waitDelivered = wait.snapshot().metrics().passengersDelivered();
        double turnDelivered = turn.snapshot().metrics().passengersDelivered();
        assertThat(turnDelivered).isGreaterThan(waitDelivered);
        assertThat(turn.stationWaiting("B")).isLessThan(wait.stationWaiting("B"));
    }

    @Test
    void trainCannotTurnBackAtATerminal() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        engine.closeSection("L1", "A", "B", 600, ClosureResponse.TURN_BACK);
        engine.run(300, 1);
        assertThat(engine.train("L1-1").stop()).isZero();
        assertThat(engine.train("L1-1").phase()).isEqualTo(TrainPhase.WAITING_SIGNAL);
    }

    @Test
    void snapshotListsClosedSectionsUntilTheyReopen() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.closeSection("L1", "E", "D", 120, ClosureResponse.TURN_BACK);
        engine.run(20, 1);

        assertThat(engine.snapshot().closedSections()).singleElement().satisfies(c -> {
            assertThat(c.lineId()).isEqualTo("L1");
            assertThat(c.fromStationId()).isEqualTo("D");
            assertThat(c.toStationId()).isEqualTo("E");
            assertThat(c.response()).isEqualTo(ClosureResponse.TURN_BACK);
            assertThat(c.remainingSeconds()).isEqualTo(100.0);
        });
        engine.run(100, 1);
        assertThat(engine.snapshot().closedSections()).isEmpty();
    }
}
