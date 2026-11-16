package com.metromind.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.infra.RedisSnapshotHistory;
import com.metromind.replay.SnapshotHistory;
import com.metromind.sim.SimulationService;
import com.metromind.sim.SimulationSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RedisStateIT extends InfrastructureIT {

    @Autowired
    SimulationService simulation;

    @Autowired
    SnapshotHistory history;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ObjectMapper mapper;

    @Test
    void latestSnapshotAndStationWaitingAreCachedInRedis() throws Exception {
        simulation.tick();
        SimulationSnapshot live = simulation.snapshot();

        SimulationSnapshot cached = mapper.readValue(redis.opsForValue().get("metromind:snapshot"),
                SimulationSnapshot.class);
        assertThat(cached.simTime()).isEqualTo(live.simTime());
        assertThat(cached.trains()).hasSameSizeAs(live.trains());
        assertThat(redis.opsForHash().size("metromind:station-waiting")).isEqualTo(live.stations().size());
    }

    @Test
    void replayHistoryIsKeptInASortedSetAndTrimmedToTheRetentionWindow() {
        assertThat(history).isInstanceOf(RedisSnapshotHistory.class);
        // 400 ticks of 5 simulated seconds run well past the 30-minute retention window.
        for (int i = 0; i < 400; i++) {
            simulation.tick();
        }
        double now = simulation.snapshot().simTime();

        SnapshotHistory.Coverage coverage = history.coverage().orElseThrow();
        assertThat(coverage.toSimTime()).isEqualTo(now);
        assertThat(coverage.fromSimTime()).isGreaterThanOrEqualTo(now - 1800);
        assertThat(coverage.toSimTime() - coverage.fromSimTime()).isGreaterThan(1700);
        assertThat(redis.opsForZSet().zCard("metromind:replay")).isEqualTo(coverage.snapshots());

        double past = now - 600;
        SimulationSnapshot replayed = history.at(past).orElseThrow();
        assertThat(replayed.simTime()).isLessThanOrEqualTo(past).isGreaterThan(past - 10);
        assertThat(history.at(coverage.fromSimTime() - 1)).isEmpty();
    }
}
