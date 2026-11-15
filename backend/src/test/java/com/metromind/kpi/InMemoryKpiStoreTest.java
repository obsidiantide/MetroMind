package com.metromind.kpi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryKpiStoreTest {

    private static MinuteKpi minute(long m, int alerts) {
        return new MinuteKpi(m, MinuteKpi.clockOf(m), 10, 0, 0, 0, 0, 0, 0, alerts, 0);
    }

    @Test
    void returnsTheMostRecentMinutesOldestFirst() {
        InMemoryKpiStore store = new InMemoryKpiStore();
        store.save(minute(482, 0));
        store.save(minute(480, 0));
        store.save(minute(481, 0));
        assertThat(store.recent(2)).extracting(MinuteKpi::minute).containsExactly(481L, 482L);
        assertThat(store.recent(10)).extracting(MinuteKpi::minute).containsExactly(480L, 481L, 482L);
    }

    @Test
    void savingAMinuteAgainReplacesIt() {
        InMemoryKpiStore store = new InMemoryKpiStore();
        store.save(minute(480, 1));
        store.save(minute(480, 3));
        assertThat(store.recent(10)).singleElement().extracting(MinuteKpi::delayAlerts).isEqualTo(3);
    }

    @Test
    void forgetsTheOldestMinutesBeyondCapacity() {
        InMemoryKpiStore store = new InMemoryKpiStore();
        for (int m = 0; m < InMemoryKpiStore.CAPACITY + 10; m++) {
            store.save(minute(m, 0));
        }
        var all = store.recent(Integer.MAX_VALUE);
        assertThat(all).hasSize(InMemoryKpiStore.CAPACITY);
        assertThat(all.get(0).minute()).isEqualTo(10);
    }
}
