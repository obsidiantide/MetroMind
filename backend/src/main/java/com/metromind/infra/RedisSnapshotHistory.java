package com.metromind.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.replay.ReplayProperties;
import com.metromind.replay.SnapshotHistory;
import com.metromind.sim.SimulationSnapshot;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Snapshot history in a Redis sorted set ({@value #KEY}) scored by simulated time, so other services can replay the
 * last minutes too. A restarted simulator starts a new timeline, so the set is cleared on startup.
 */
@Component
@ConditionalOnProperty(name = "metromind.redis.enabled", havingValue = "true")
public class RedisSnapshotHistory extends SnapshotHistory {

    static final String KEY = "metromind:replay";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisSnapshotHistory(ReplayProperties props, StringRedisTemplate redis, ObjectMapper mapper) {
        super(props);
        this.redis = redis;
        this.mapper = mapper;
    }

    @PostConstruct
    void startNewTimeline() {
        redis.delete(KEY);
    }

    @Override
    public Optional<SimulationSnapshot> at(double simTime) {
        Set<String> found = redis.opsForZSet().reverseRangeByScore(KEY, Double.NEGATIVE_INFINITY, simTime, 0, 1);
        return found == null ? Optional.empty() : found.stream().findFirst().map(this::read);
    }

    @Override
    public Optional<Coverage> coverage() {
        ZSetOperations<String, String> zset = redis.opsForZSet();
        Long size = zset.zCard(KEY);
        var first = zset.rangeWithScores(KEY, 0, 0);
        var last = zset.reverseRangeWithScores(KEY, 0, 0);
        if (size == null || size == 0 || first == null || first.isEmpty() || last == null || last.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Coverage(first.iterator().next().getScore(), last.iterator().next().getScore(),
                size.intValue()));
    }

    @Override
    protected void store(SimulationSnapshot snapshot) {
        try {
            redis.opsForZSet().add(KEY, mapper.writeValueAsString(snapshot), snapshot.simTime());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise snapshot", e);
        }
    }

    @Override
    protected void dropBefore(double simTime) {
        // ZREMRANGEBYSCORE bounds are inclusive; stop just below simTime to keep a snapshot taken exactly then.
        redis.opsForZSet().removeRangeByScore(KEY, Double.NEGATIVE_INFINITY, Math.nextDown(simTime));
    }

    private SimulationSnapshot read(String json) {
        try {
            return mapper.readValue(json, SimulationSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read stored snapshot", e);
        }
    }
}
