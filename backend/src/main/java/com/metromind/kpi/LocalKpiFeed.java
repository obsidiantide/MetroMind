package com.metromind.kpi;

import com.metromind.sim.SimEvent;
import com.metromind.sim.SimulationSnapshot;
import com.metromind.sim.SnapshotListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Without Kafka, feeds every snapshot straight into the {@link KpiRecorder} so per-minute KPIs are still available.
 * Sends exactly what the Kafka publisher would: every train's position, the network metrics and new events.
 */
@Component
@ConditionalOnProperty(name = "metromind.kafka.enabled", havingValue = "false")
public class LocalKpiFeed implements SnapshotListener {

    private final KpiRecorder recorder;
    private double lastEventTime = Double.NEGATIVE_INFINITY;

    public LocalKpiFeed(KpiRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void onSnapshot(SimulationSnapshot snapshot) {
        for (SimulationSnapshot.TrainView t : snapshot.trains()) {
            recorder.position(snapshot.simTime(), t);
        }
        recorder.metrics(snapshot.simTime(), snapshot.metrics());
        double newest = lastEventTime;
        for (SimEvent e : snapshot.recentEvents()) {
            if (e.simTime() > lastEventTime) {
                recorder.event(e);
                newest = Math.max(newest, e.simTime());
            }
        }
        lastEventTime = newest;
    }
}
