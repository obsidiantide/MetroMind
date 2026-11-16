package com.metromind.it;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The infrastructure of {@code docker-compose.yml} as Testcontainers, wired into Spring Boot through
 * {@link ServiceConnection}. Images match the compose file so tests exercise the same versions as a local stack.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InfraContainers {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgis() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        // Kafka 3.9 also advertises the controller listener and rejects the 0.0.0.0 that Testcontainers binds it
        // to; the controller is only reached from inside the container, so bind it to localhost instead.
        return new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"))
                .withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://localhost:9094");
    }
}
