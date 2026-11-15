package com.metromind.replay;

import com.metromind.sim.SimulationSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySnapshotHistoryTest {

    private static SimulationSnapshot at(double simTime) {
        return new SimulationSnapshot(simTime, "", "NO_CONTROL", List.of(), List.of(), List.of(),
                new SimulationSnapshot.Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List.of(), List.of());
    }

    @Test
    void returnsTheNewestSnapshotAtOrBeforeTheRequestedTime() {
        var history = new InMemorySnapshotHistory(new ReplayProperties(30, 10));
        for (int t = 1000; t <= 1100; t += 5) {
            history.onSnapshot(at(t));
        }
        // Sampled every 10 s: 1000, 1010, ..., 1100.
        assertThat(history.coverage()).hasValue(new SnapshotHistory.Coverage(1000, 1100, 11));
        assertThat(history.at(1037)).map(SimulationSnapshot::simTime).hasValue(1030.0);
        assertThat(history.at(1040)).map(SimulationSnapshot::simTime).hasValue(1040.0);
        assertThat(history.at(5000)).map(SimulationSnapshot::simTime).hasValue(1100.0);
        assertThat(history.at(999)).isEmpty();
    }

    @Test
    void dropsSnapshotsOlderThanTheRetention() {
        var history = new InMemorySnapshotHistory(new ReplayProperties(1, 10));
        for (int t = 0; t <= 300; t += 10) {
            history.onSnapshot(at(t));
        }
        assertThat(history.coverage()).hasValue(new SnapshotHistory.Coverage(240, 300, 7));
        assertThat(history.at(200)).isEmpty();
    }

    @Test
    void aPausedClockStoresNothingNew() {
        var history = new InMemorySnapshotHistory(new ReplayProperties(30, 10));
        history.onSnapshot(at(500));
        history.onSnapshot(at(500));
        history.onSnapshot(at(500));
        assertThat(history.coverage()).map(SnapshotHistory.Coverage::snapshots).hasValue(1);
    }

    @Test
    void isEmptyBeforeTheFirstSnapshot() {
        var history = new InMemorySnapshotHistory(new ReplayProperties(30, 10));
        assertThat(history.coverage()).isEmpty();
        assertThat(history.at(0)).isEmpty();
    }
}
