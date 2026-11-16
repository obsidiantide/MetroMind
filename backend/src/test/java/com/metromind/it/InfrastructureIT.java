package com.metromind.it;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base of the integration tests: the full application against real containers. All subclasses share one Spring
 * context and therefore one set of containers. The scheduler ticks once at startup and then stays idle for the
 * duration of the run, so tests drive the simulation themselves and see no background writes.
 */
@SpringBootTest(properties = {
        "metromind.sim.tick-millis=3600000",
        "metromind.kafka.enabled=false",
        "metromind.redis.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "management.health.redis.enabled=false"})
@Import(InfraContainers.class)
public abstract class InfrastructureIT {
}
