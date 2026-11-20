package com.metromind.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.sim.SimEvent;
import com.metromind.sim.SimulationSnapshot;
import com.metromind.sim.SnapshotListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Streams train telemetry and simulation events to Kafka so other services (analytics, alerting, replay)
 * can consume them independently of the simulator. Messages are {@link VersionedPayloads versioned}, so a consumer
 * of another build reads them, and the topics' retained backlog, across schema changes.
 */
@Component
@ConditionalOnProperty(name = "metromind.kafka.enabled", havingValue = "true")
public class KafkaSnapshotPublisher implements SnapshotListener {

    public static final String POSITIONS_TOPIC = "metromind.train-positions";
    public static final String EVENTS_TOPIC = "metromind.events";
    /** Network-wide metrics (waiting passengers, headway regularity...), one message per tick. */
    public static final String METRICS_TOPIC = "metromind.network-metrics";

    private final KafkaTemplate<String, String> kafka;
    private final VersionedPayloads payloads;
    private double lastEventTime = Double.NEGATIVE_INFINITY;

    public KafkaSnapshotPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.payloads = new VersionedPayloads(mapper);
    }

    @Override
    public void onSnapshot(SimulationSnapshot snapshot) {
        for (SimulationSnapshot.TrainView t : snapshot.trains()) {
            kafka.send(POSITIONS_TOPIC, t.id(), json(new Position(snapshot.simTime(), t)));
        }
        double newest = lastEventTime;
        for (SimEvent e : snapshot.recentEvents()) {
            if (e.simTime() > lastEventTime) {
                kafka.send(EVENTS_TOPIC, e.subjectId(), json(e));
                newest = Math.max(newest, e.simTime());
            }
        }
        lastEventTime = newest;
        kafka.send(METRICS_TOPIC, "network", json(new NetworkMetrics(snapshot.simTime(), snapshot.metrics())));
    }

    public record Position(double simTime, SimulationSnapshot.TrainView train) {
    }

    public record NetworkMetrics(double simTime, SimulationSnapshot.Metrics metrics) {
    }

    private String json(Object o) {
        return payloads.write(o);
    }
}
