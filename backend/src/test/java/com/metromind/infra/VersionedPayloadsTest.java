package com.metromind.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.sim.SimulationSnapshot;
import com.metromind.sim.TrainPhase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionedPayloadsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final VersionedPayloads payloads = new VersionedPayloads(mapper);

    private static SimulationSnapshot.TrainView train(String patternId) {
        return new SimulationSnapshot.TrainView("RED-1", "RED", patternId, "R1", "R2", "R5", 0.3, 40.4, 49.8,
                TrainPhase.RUNNING, 12, 300, 900, false);
    }

    private static SimulationSnapshot snapshot() {
        var station = new SimulationSnapshot.StationView("R1", 40, 55, "LOW", 0, 2,
                List.of(new SimulationSnapshot.QueueView("RED", "R5", 40)), false);
        var metrics = new SimulationSnapshot.Metrics(12, 12, 40, 100, 80, 5, 2, 0, 0.1, 4, 1, 0, 0, Map.of(), 0);
        return new SimulationSnapshot(28_800, "08:00:00", "BALANCED", List.of(train("RED/R9")), List.of(station),
                List.of(), metrics, List.of(), List.of());
    }

    @Test
    void writesTheCurrentVersionAndReadsItBack() throws Exception {
        String json = payloads.write(snapshot());

        assertThat(mapper.readTree(json).path(VersionedPayloads.VERSION_FIELD).asInt())
                .isEqualTo(VersionedPayloads.CURRENT_VERSION);
        assertThat(payloads.read(json, VersionedPayloads.Kind.SNAPSHOT, SimulationSnapshot.class))
                .isEqualTo(snapshot());
    }

    @Test
    void upgradesAnUnversionedSnapshotWrittenBeforePatternsAndQueues() throws Exception {
        // A snapshot as an early build stored it: no version, no patternId, no queues, no incident lists.
        String old = """
                {"simTime": 28800, "clock": "08:00:00", "strategy": "BALANCED",
                 "trains": [{"id": "RED-1", "lineId": "RED", "fromStationId": "R1", "toStationId": "R2",
                             "progress": 0.3, "lat": 40.4, "lon": 49.8, "phase": "RUNNING", "delaySeconds": 12,
                             "load": 300, "capacity": 900, "failed": false}],
                 "stations": [{"id": "R1", "waiting": 40, "predictedWaiting15m": 55, "overloadRisk": "LOW"}],
                 "recentEvents": [],
                 "metrics": {"averageDelaySeconds": 12, "maxDelaySeconds": 12, "totalWaiting": 40}}
                """;

        SimulationSnapshot s = payloads.read(old, VersionedPayloads.Kind.SNAPSHOT, SimulationSnapshot.class);

        assertThat(s.trains()).singleElement().satisfies(t -> {
            assertThat(t.patternId()).isEqualTo("RED");
            assertThat(t.towardsStationId()).isNull();
        });
        assertThat(s.stations()).singleElement().satisfies(st -> assertThat(st.queues()).isEmpty());
        assertThat(s.closedSections()).isEmpty();
        assertThat(s.activeIncidents()).isEmpty();
        assertThat(s.metrics().trunkHeadwayCv()).isEmpty();
    }

    @Test
    void upgradesAnUnversionedPositionWithoutAPattern() throws Exception {
        String old = """
                {"simTime": 28800, "train": {"id": "RED-1", "lineId": "RED", "fromStationId": "R1",
                 "toStationId": "R2", "phase": "DWELLING", "delaySeconds": 30, "load": 10, "capacity": 900}}
                """;

        var p = payloads.read(old, VersionedPayloads.Kind.POSITION, KafkaSnapshotPublisher.Position.class);

        assertThat(p.train().patternId()).isEqualTo("RED");
        assertThat(p.train().delaySeconds()).isEqualTo(30);
    }

    @Test
    void keepsAPatternTheEntryAlreadyHas() throws Exception {
        String old = mapper.writeValueAsString(new KafkaSnapshotPublisher.Position(1, train("RED/R9")));

        var p = payloads.read(old, VersionedPayloads.Kind.POSITION, KafkaSnapshotPublisher.Position.class);

        assertThat(p.train().patternId()).isEqualTo("RED/R9");
    }

    @Test
    void readsWhatItKnowsOfAPayloadFromANewerWriter() throws Exception {
        String newer = """
                {"schemaVersion": 99, "simTime": 5, "train": {"id": "RED-1", "lineId": "RED", "patternId": "RED",
                 "phase": "RUNNING", "doorsOpen": true}}
                """;

        var p = payloads.read(newer, VersionedPayloads.Kind.POSITION, KafkaSnapshotPublisher.Position.class);

        assertThat(p.simTime()).isEqualTo(5);
        assertThat(p.train().id()).isEqualTo("RED-1");
    }

    @Test
    void rejectsAPayloadThatIsNotAnObject() {
        assertThatThrownBy(() -> payloads.read("[1, 2]", VersionedPayloads.Kind.EVENT, Object.class))
                .isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("Expected a JSON object");
    }
}
