package com.metromind.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.infra.KafkaSnapshotPublisher;
import com.metromind.kpi.KpiStore;
import com.metromind.kpi.MinuteKpi;
import com.metromind.sim.SimulationService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class KafkaTelemetryIT extends InfrastructureIT {

    @Autowired
    SimulationService simulation;

    @Autowired
    KpiStore store;

    @Autowired
    ConsumerFactory<String, String> consumers;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    KafkaAdmin admin;

    @Test
    void telemetryFlowsThroughKafkaIntoStoredMinuteKpis() throws Exception {
        // Each topic has its own listener, so a backlog left by earlier tests would let the light metrics topic
        // run ahead of the positions topic and close minutes before their positions arrive.
        awaitKpiConsumerCaughtUp();
        simulation.tick();
        long minute = (long) Math.floor(simulation.snapshot().simTime() / 60);
        int trains = simulation.snapshot().trains().size();
        // Three simulated minutes: enough for the consumer's watermark to close the minute above. Ticks are paced
        // like the live scheduler (if faster), so the 30 s grace period still spans some real time.
        for (int i = 0; i < 36; i++) {
            simulation.tick();
            Thread.sleep(50);
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(store.recent(500)).filteredOn(k -> k.minute() == minute)
                        .singleElement()
                        .satisfies(k -> {
                            assertThat(k.trainsReporting()).isEqualTo(trains);
                            assertThat(k.averageWaiting()).isPositive();
                        }));
    }

    @Test
    void positionsAreKeyedByTrainSoEachTrainStaysOnOnePartition() throws Exception {
        Properties fresh = new Properties();
        fresh.put(ConsumerConfig.GROUP_ID_CONFIG, "it-positions");
        try (Consumer<String, String> consumer = consumers.createConsumer("it-positions", null, null, fresh)) {
            consumer.subscribe(List.of(KafkaSnapshotPublisher.POSITIONS_TOPIC));
            simulation.tick();

            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                return received.size() >= simulation.snapshot().trains().size();
            });

            for (ConsumerRecord<String, String> r : received) {
                var position = mapper.readValue(r.value(), KafkaSnapshotPublisher.Position.class);
                assertThat(r.key()).isEqualTo(position.train().id());
            }
        }
    }

    private void awaitKpiConsumerCaughtUp() {
        try (AdminClient client = AdminClient.create(admin.getConfigurationProperties())) {
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                var committed = client.listConsumerGroupOffsets("metromind-kpi").partitionsToOffsetAndMetadata()
                        .get();
                var latest = client.listOffsets(committed.keySet().stream()
                                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest())))
                        .all().get();
                var topics = committed.keySet().stream().map(tp -> tp.topic()).toList();
                return topics.containsAll(List.of(KafkaSnapshotPublisher.POSITIONS_TOPIC,
                        KafkaSnapshotPublisher.METRICS_TOPIC)) && committed.entrySet().stream()
                        .allMatch(e -> e.getValue().offset() == latest.get(e.getKey()).offset());
            });
        }
    }
}
