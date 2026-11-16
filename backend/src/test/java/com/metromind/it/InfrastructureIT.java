package com.metromind.it;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base of the integration tests: the full application against real containers. All subclasses share one Spring
 * context and therefore one set of containers. The scheduler ticks once at startup and then stays idle for the
 * duration of the run (one tick every ~17 minutes), so tests drive the simulation themselves and see no background
 * writes. The speed keeps each tick at the usual 5 simulated seconds.
 */
@SpringBootTest(properties = {
        "metromind.sim.tick-millis=1000000",
        "metromind.sim.speed=0.005",
        "metromind.kafka.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"})
@Import(InfraContainers.class)
public abstract class InfrastructureIT {
}
