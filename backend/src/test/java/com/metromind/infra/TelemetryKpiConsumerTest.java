package com.metromind.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.kpi.InMemoryKpiStore;
import com.metromind.kpi.KpiRecorder;
import com.metromind.kpi.MinuteKpi;
import com.metromind.sim.SimEvent;
import com.metromind.sim.SimulationSnapshot;
import com.metromind.sim.TrainPhase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Feeds the consumer the same JSON {@link KafkaSnapshotPublisher} writes, without a broker. */
class TelemetryKpiConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final KpiRecorder recorder = new KpiRecorder(new InMemoryKpiStore());
    private final TelemetryKpiConsumer consumer = new TelemetryKpiConsumer(recorder, mapper);

    private String json(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private String position(double simTime, String trainId, double delay) throws Exception {
        return json(new KafkaSnapshotPublisher.Position(simTime, new SimulationSnapshot.TrainView(trainId, "RED",
                "RED", "R1", "R2", "R5", 0.3, 40.4, 49.8, TrainPhase.RUNNING, delay, 120, 240, false)));
    }

    @Test
    void turnsPublishedTelemetryIntoMinuteKpis() throws Exception {
        consumer.onPosition(position(28_800, "RED-1", 40));
        consumer.onPosition(position(28_830, "RED-2", 80));
        consumer.onMetrics(json(new KafkaSnapshotPublisher.NetworkMetrics(28_810,
                new SimulationSnapshot.Metrics(60, 80, 900, 0, 0, 0, 0, 0, 0.25, 6, 0, 0, 0, Map.of()))));
        consumer.onEvent(json(new SimEvent(28_820, SimEvent.Type.DELAY_DETECTED, "RED-2", "late")));
        consumer.onPosition(position(28_900, "RED-1", 0));
        consumer.onMetrics(json(new KafkaSnapshotPublisher.NetworkMetrics(28_900,
                new SimulationSnapshot.Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()))));

        assertThat(recorder.recent(10)).singleElement().satisfies(k -> {
            assertThat(k.clock()).isEqualTo("08:00");
            assertThat(k.trainsReporting()).isEqualTo(2);
            assertThat(k.averageDelaySeconds()).isEqualTo(60.0);
            assertThat(k.averageLoadFactor()).isEqualTo(0.5);
            assertThat(k.averageWaiting()).isEqualTo(900.0);
            assertThat(k.averageHeadwayCv()).isEqualTo(0.25);
            assertThat(k.delayAlerts()).isEqualTo(1);
        });
    }

    @Test
    void readsPublishedMessagesAndOnesLeftOnTheTopicsByAnOlderBuild() throws Exception {
        var publisher = new VersionedPayloads(mapper);
        consumer.onPosition(publisher.write(new KafkaSnapshotPublisher.Position(28_800,
                new SimulationSnapshot.TrainView("RED-1", "RED", "RED", "R1", "R2", "R5", 0.3, 40.4, 49.8,
                        TrainPhase.RUNNING, 20, 120, 240, false))));
        // Unversioned, from before trains carried their service pattern.
        consumer.onPosition("""
                {"simTime": 28830, "train": {"id": "RED-2", "lineId": "RED", "fromStationId": "R2",
                 "toStationId": "R3", "phase": "RUNNING", "delaySeconds": 40, "load": 60, "capacity": 240}}
                """);
        recorder.flush();

        assertThat(recorder.recent(10)).singleElement().satisfies(k -> {
            assertThat(k.trainsReporting()).isEqualTo(2);
            assertThat(k.averageDelaySeconds()).isEqualTo(30.0);
        });
    }

    @Test
    void skipsMalformedMessages() throws Exception {
        consumer.onPosition("{not json");
        consumer.onEvent("{\"simTime\": 1, \"type\": \"NO_SUCH_TYPE\"}");
        consumer.onPosition(position(28_800, "RED-1", 10));
        recorder.flush();
        assertThat(recorder.recent(10)).extracting(MinuteKpi::trainsReporting).containsExactly(1);
    }
}
