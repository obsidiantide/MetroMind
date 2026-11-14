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
}
