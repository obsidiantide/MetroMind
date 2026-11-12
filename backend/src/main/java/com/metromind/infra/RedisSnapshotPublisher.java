package com.metromind.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.sim.SimulationSnapshot;
import com.metromind.sim.SnapshotListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Keeps the latest state in Redis: full snapshot + a hash of waiting passengers per station. */
@Component
@ConditionalOnProperty(name = "metromind.redis.enabled", havingValue = "true")
public class RedisSnapshotPublisher implements SnapshotListener {

    static final String SNAPSHOT_KEY = "metromind:snapshot";
    static final String WAITING_KEY = "metromind:station-waiting";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisSnapshotPublisher(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public void onSnapshot(SimulationSnapshot snapshot) {
        try {
            redis.opsForValue().set(SNAPSHOT_KEY, mapper.writeValueAsString(snapshot));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        Map<String, String> waiting = new HashMap<>();
        snapshot.stations().forEach(s -> waiting.put(s.id(), String.valueOf(Math.round(s.waiting()))));
        redis.opsForHash().putAll(WAITING_KEY, waiting);
    }
}
