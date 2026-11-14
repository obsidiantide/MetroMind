package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PlatformCrowdingTest {

    private static final double START = 8 * 3600;

    private static MetroNetwork singleLineWithCapacity(int trainCapacity) {
        MetroNetwork base = TestNetworks.singleLine();
        Line line = base.line("L1");
        return new MetroNetwork("Test", base.stations(), List.of(new Line(line.id(), line.name(), line.color(),
                line.stationIds(), line.runSeconds(), line.trainCount(), trainCapacity)));
    }

    @Test
    void nobodyIsLeftBehindWhenTrainsHaveRoom() {
        SimulationEngine engine = new SimulationEngine(singleLineWithCapacity(10_000), START,
                HeadwayStrategy.NO_CONTROL);
        engine.run(3600, 1);
        assertThat(engine.snapshot().metrics().passengersLeftBehind()).isZero();
    }

    @Test
    void passengersWhoDoNotFitAreCountedAsLeftBehind() {
        SimulationEngine engine = new SimulationEngine(singleLineWithCapacity(40), START, HeadwayStrategy.NO_CONTROL);
        engine.run(3600, 1);
        SimulationSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.metrics().passengersLeftBehind()).isPositive();
        double perStation = snapshot.stations().stream().mapToDouble(SimulationSnapshot.StationView::leftBehind).sum();
        assertThat(perStation).isCloseTo(snapshot.metrics().passengersLeftBehind(), within(1e-6));
        // C is the busiest station on the line.
        assertThat(engine.stationLeftBehind("C")).isPositive();
    }
}
