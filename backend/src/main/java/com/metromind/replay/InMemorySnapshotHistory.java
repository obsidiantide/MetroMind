package com.metromind.replay;

import com.metromind.sim.SimulationSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/** Snapshot history for runs without Redis, kept in the simulator's memory. */
@Component
@ConditionalOnProperty(name = "metromind.redis.enabled", havingValue = "false")
public class InMemorySnapshotHistory extends SnapshotHistory {

    private final ConcurrentSkipListMap<Double, SimulationSnapshot> snapshots = new ConcurrentSkipListMap<>();

    public InMemorySnapshotHistory(ReplayProperties props) {
        super(props);
    }

    @Override
    public Optional<SimulationSnapshot> at(double simTime) {
        return Optional.ofNullable(snapshots.floorEntry(simTime)).map(Map.Entry::getValue);
    }

    @Override
    public Optional<Coverage> coverage() {
        var first = snapshots.firstEntry();
        var last = snapshots.lastEntry();
        if (first == null || last == null) {
            return Optional.empty();
        }
        return Optional.of(new Coverage(first.getKey(), last.getKey(), snapshots.size()));
    }

    @Override
    protected void store(SimulationSnapshot snapshot) {
        snapshots.put(snapshot.simTime(), snapshot);
    }

    @Override
    protected void dropBefore(double simTime) {
        snapshots.headMap(simTime).clear();
    }
}
