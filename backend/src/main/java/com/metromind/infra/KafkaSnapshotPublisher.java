package com.metromind.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.sim.SimEvent;
import com.metromind.sim.SimulationSnapshot;
import com.metromind.sim.SnapshotListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Streams train telemetry and simulation events to Kafka so other services (analytics, alerting, replay)
 * can consume them independently of the simulator.
 */
@Component
@ConditionalOnProperty(name = "metromind.kafka.enabled", havingValue = "true")
public class KafkaSnapshotPublisher implements SnapshotListener {

    public static final String POSITIONS_TOPIC = "metromind.train-positions";
    public static final String EVENTS_TOPIC = "metromind.events";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private double lastEventTime = Double.NEGATIVE_INFINITY;

    public KafkaSnapshotPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
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
    }

    record Position(double simTime, SimulationSnapshot.TrainView train) {
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
