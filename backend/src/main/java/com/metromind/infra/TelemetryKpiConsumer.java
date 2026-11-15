package com.metromind.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.kpi.KpiRecorder;
import com.metromind.sim.SimEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the telemetry topics written by {@link KafkaSnapshotPublisher} and aggregates them into per-minute KPIs.
 * It only depends on the topics, so it can run in a separate instance from the simulator. A malformed message is
 * logged and skipped rather than blocking its partition.
 */
@Component
@ConditionalOnProperty(name = "metromind.kafka.enabled", havingValue = "true")
public class TelemetryKpiConsumer {

    static final String GROUP_ID = "metromind-kpi";

    private static final Logger log = LoggerFactory.getLogger(TelemetryKpiConsumer.class);

    private final KpiRecorder recorder;
    private final ObjectMapper mapper;

    public TelemetryKpiConsumer(KpiRecorder recorder, ObjectMapper mapper) {
        this.recorder = recorder;
        this.mapper = mapper;
    }

    @KafkaListener(topics = KafkaSnapshotPublisher.POSITIONS_TOPIC, groupId = GROUP_ID)
    public void onPosition(String json) {
        var p = read(json, KafkaSnapshotPublisher.Position.class);
        if (p != null) {
            recorder.position(p.simTime(), p.train());
        }
    }

    @KafkaListener(topics = KafkaSnapshotPublisher.METRICS_TOPIC, groupId = GROUP_ID)
    public void onMetrics(String json) {
        var m = read(json, KafkaSnapshotPublisher.NetworkMetrics.class);
        if (m != null) {
            recorder.metrics(m.simTime(), m.metrics());
        }
    }

    @KafkaListener(topics = KafkaSnapshotPublisher.EVENTS_TOPIC, groupId = GROUP_ID)
    public void onEvent(String json) {
        var e = read(json, SimEvent.class);
        if (e != null) {
            recorder.event(e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("Skipping malformed {} message: {}", type.getSimpleName(), e.getOriginalMessage());
            return null;
        }
    }
}
