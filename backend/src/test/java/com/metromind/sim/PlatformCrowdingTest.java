package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PlatformCrowdingTest {

    private static final double START = 8 * 3600;

    private static MetroNetwork singleLineWithCapacity(int trainCapacity) {
        return singleLineWithCapacity(trainCapacity, null);
    }

    /** {@link TestNetworks#singleLine()} with the given train size and, optionally, a platform limit at C. */
    private static MetroNetwork singleLineWithCapacity(int trainCapacity, Integer platformAtC) {
        MetroNetwork base = TestNetworks.singleLine();
        Line line = base.line("L1");
        List<Station> stations = base.stations().stream()
                .map(s -> s.id().equals("C") ? new Station(s.id(), s.name(), s.lat(), s.lon(),
                        s.baseArrivalsPerMinute(), platformAtC) : s)
                .toList();
        return new MetroNetwork("Test", stations, List.of(new Line(line.id(), line.name(), line.color(),
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

    @Test
    void fullPlatformHoldsNewPassengersAtTheEntrance() {
        SimulationEngine engine = new SimulationEngine(singleLineWithCapacity(40, 60), START,
                HeadwayStrategy.NO_CONTROL);
        double heldMax = 0;
        for (int i = 0; i < 3600; i++) {
            engine.step(1);
            assertThat(engine.stationWaiting("C")).isLessThanOrEqualTo(60 + 1e-9);
            heldMax = Math.max(heldMax, engine.stationHeldAtEntrance("C"));
        }
        assertThat(heldMax).isPositive();
        // Unlimited stations are unaffected.
        assertThat(engine.stationHeldAtEntrance("B")).isZero();
    }

    @Test
    void passengersHeldAtTheEntranceAreNotLost() {
        SimulationEngine limited = new SimulationEngine(singleLineWithCapacity(40, 60), START,
                HeadwayStrategy.NO_CONTROL);
        SimulationEngine unlimited = new SimulationEngine(singleLineWithCapacity(40), START,
                HeadwayStrategy.NO_CONTROL);
        limited.run(3600, 1);
        unlimited.run(3600, 1);
        assertThat(inSystemOrDelivered(limited)).isCloseTo(inSystemOrDelivered(unlimited), within(1e-6));
        // Waiting outside still costs time.
        assertThat(limited.stationWaitSeconds("C")).isPositive();
    }

    private static double inSystemOrDelivered(SimulationEngine engine) {
        double total = engine.trains().stream().mapToDouble(TrainState::passengers).sum();
        for (Station s : engine.network().stations()) {
            total += engine.stationWaiting(s.id()) + engine.stationHeldAtEntrance(s.id())
                    + engine.stationDelivered(s.id());
        }
        return total;
    }

    @Test
    void snapshotReportsEntranceQueuesAndCountsThemInTheForecast() {
        SimulationEngine engine = new SimulationEngine(singleLineWithCapacity(40, 60), START,
                HeadwayStrategy.NO_CONTROL);
        while (engine.stationHeldAtEntrance("C") <= 0) {
            engine.step(1);
        }
        SimulationSnapshot snapshot = engine.snapshot();
        SimulationSnapshot.StationView c = snapshot.stations().stream()
                .filter(s -> s.id().equals("C")).findFirst().orElseThrow();
        assertThat(c.heldAtEntrance()).isEqualTo(engine.stationHeldAtEntrance("C"));
        assertThat(c.waiting()).isLessThanOrEqualTo(60 + 1e-9);
        assertThat(snapshot.metrics().passengersHeldAtEntrance()).isEqualTo(c.heldAtEntrance());
        assertThat(engine.predictWaiting("C", 0)).isGreaterThanOrEqualTo(c.heldAtEntrance());
    }
}
