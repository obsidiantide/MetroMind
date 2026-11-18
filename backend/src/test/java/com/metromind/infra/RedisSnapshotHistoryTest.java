package com.metromind.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.replay.ReplayProperties;
import com.metromind.sim.SimEvent;
import com.metromind.sim.SimulationSnapshot;
import com.metromind.sim.TrainPhase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisSnapshotHistoryTest {

    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zset = mock(ZSetOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RedisSnapshotHistory history;

    RedisSnapshotHistoryTest() {
        when(redis.opsForZSet()).thenReturn(zset);
        history = new RedisSnapshotHistory(new ReplayProperties(1, 10), redis, mapper);
    }

    private static SimulationSnapshot snapshot(double simTime) {
        return new SimulationSnapshot(simTime, "08:00:00", "BALANCED",
                List.of(new SimulationSnapshot.TrainView("RED-1", "RED", "RED", "R1", "R2", 0.5, 40.4, 49.8,
                        TrainPhase.RUNNING, 12, 80, 200, false)),
                List.of(), List.of(new SimEvent(simTime, SimEvent.Type.DELAY_DETECTED, "RED-1", "late")),
                new SimulationSnapshot.Metrics(1, 2, 3, 4, 5, 6, 7, 8, 0.1, 9, 1, 0, 0), List.of(), List.of());
    }

    @Test
    void storesSnapshotsScoredBySimTimeAndTrimsStrictlyOlderOnes() {
        history.onSnapshot(snapshot(28_800));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(zset).add(eq(RedisSnapshotHistory.KEY), json.capture(), eq(28_800.0));
        assertThat(json.getValue()).contains("\"strategy\":\"BALANCED\"");
        ArgumentCaptor<Double> max = ArgumentCaptor.forClass(Double.class);
        verify(zset).removeRangeByScore(eq(RedisSnapshotHistory.KEY), eq(Double.NEGATIVE_INFINITY), max.capture());
        assertThat(max.getValue()).isLessThan(28_740.0).isGreaterThan(28_739.999);
    }

    @Test
    void readsBackTheNewestSnapshotAtOrBeforeATime() throws Exception {
        SimulationSnapshot stored = snapshot(28_800);
        when(zset.reverseRangeByScore(RedisSnapshotHistory.KEY, Double.NEGATIVE_INFINITY, 28_805, 0, 1))
                .thenReturn(new LinkedHashSet<>(List.of(mapper.writeValueAsString(stored))));

        assertThat(history.at(28_805)).hasValue(stored);
    }

    @Test
    void nothingStoredMeansNoSnapshotAndNoCoverage() {
        when(zset.reverseRangeByScore(anyString(), anyDouble(), anyDouble(), eq(0L), eq(1L))).thenReturn(Set.of());
        when(zset.zCard(RedisSnapshotHistory.KEY)).thenReturn(0L);

        assertThat(history.at(1)).isEmpty();
        assertThat(history.coverage()).isEmpty();
    }
}
