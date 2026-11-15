package com.metromind.replay;

import com.metromind.sim.SimulationSnapshot;
import com.metromind.sim.SnapshotListener;

import java.util.Optional;

/**
 * The last few minutes of simulation snapshots, for replay and time travel. Snapshots are sampled every
 * {@link ReplayProperties#intervalSeconds()} of simulated time (a paused clock stores nothing new) and those older
 * than {@link ReplayProperties#retentionSeconds()} behind the newest are dropped.
 */
public abstract class SnapshotHistory implements SnapshotListener {

    /** Time span held, and how many snapshots cover it. */
    public record Coverage(double fromSimTime, double toSimTime, int snapshots) {
    }

    private final ReplayProperties props;
    private double lastStored = Double.NEGATIVE_INFINITY;

    protected SnapshotHistory(ReplayProperties props) {
        this.props = props;
    }

    @Override
    public final synchronized void onSnapshot(SimulationSnapshot snapshot) {
        if (snapshot.simTime() - lastStored < props.intervalSeconds()) {
            return;
        }
        store(snapshot);
        lastStored = snapshot.simTime();
        dropBefore(snapshot.simTime() - props.retentionSeconds());
    }

    /** The newest stored snapshot taken at or before {@code simTime}, if it is still held. */
    public abstract Optional<SimulationSnapshot> at(double simTime);

    /** What is held, or empty before the first snapshot. */
    public abstract Optional<Coverage> coverage();

    protected abstract void store(SimulationSnapshot snapshot);

    /** Removes every snapshot taken strictly before {@code simTime}. */
    protected abstract void dropBefore(double simTime);
}
