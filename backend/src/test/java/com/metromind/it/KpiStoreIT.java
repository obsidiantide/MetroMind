package com.metromind.it;

import com.metromind.infra.JdbcKpiStore;
import com.metromind.kpi.KpiStore;
import com.metromind.kpi.MinuteKpi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class KpiStoreIT extends InfrastructureIT {

    /** Far beyond any minute the idle simulation reaches; each test writes its own range of minutes. */
    private static final long BASE = 1_000_000;

    @Autowired
    KpiStore store;

    private static MinuteKpi kpi(long minute, String clock, double averageDelay, int alerts) {
        return new MinuteKpi(minute, clock, 12, averageDelay, averageDelay * 2, 0.6, 340, 90, 0.18, alerts, 0);
    }

    @Test
    void reaggregatedMinuteOverwritesTheStoredRow() {
        assertThat(store).isInstanceOf(JdbcKpiStore.class);

        store.save(kpi(BASE + 100, "09:40", 30, 1));
        store.save(kpi(BASE + 100, "09:40", 45, 3));

        assertThat(store.recent(10)).filteredOn(k -> k.minute() == BASE + 100)
                .containsExactly(kpi(BASE + 100, "09:40", 45, 3));
    }

    @Test
    void recentReturnsTheNewestMinutesInChronologicalOrder() {
        store.save(kpi(BASE + 202, "11:22", 10, 0));
        store.save(kpi(BASE + 200, "11:20", 20, 0));
        store.save(kpi(BASE + 201, "11:21", 30, 0));

        assertThat(store.recent(2)).extracting(MinuteKpi::minute).containsExactly(BASE + 201, BASE + 202);
    }
}
