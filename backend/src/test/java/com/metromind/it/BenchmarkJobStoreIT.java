package com.metromind.it;

import com.metromind.incident.BenchmarkJobStore;
import com.metromind.incident.BenchmarkJobs;
import com.metromind.incident.BenchmarkReport;
import com.metromind.incident.TimeOfDayReport;
import com.metromind.infra.JdbcBenchmarkJobStore;
import com.metromind.sim.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class BenchmarkJobStoreIT extends InfrastructureIT {

    @Autowired
    BenchmarkJobStore<BenchmarkReport> store;

    @Autowired
    BenchmarkJobStore<TimeOfDayReport> dayStore;

    @Autowired
    SimulationService simulation;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aFinishedDayJobIsStoredInItsOwnTable() {
        assertThat(dayStore).isInstanceOf(JdbcBenchmarkJobStore.class);

        String id = simulation.submitDayBenchmark(List.of("08:00", "21:00"), 1, 5, 300,
                EnumSet.of(BenchmarkReport.IncidentKind.DELAY), BenchmarkReport.RankingMetric.WAIT_PER_BOARDING, 0,
                0).id();
        await().atMost(Duration.ofSeconds(60)).until(() -> dayStore.find(id).isPresent());

        BenchmarkJobs.Job<TimeOfDayReport> stored = dayStore.find(id).orElseThrow();
        assertThat(stored.status()).isEqualTo(BenchmarkJobs.Status.SUCCEEDED);
        assertThat(stored.report()).isEqualTo(simulation.dayBenchmarkJob(id).orElseThrow().report());
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(report -> 'periods') FROM day_benchmark_job"
                + " WHERE id = ?", Integer.class, id)).isEqualTo(2);
        assertThat(store.find(id)).isEmpty();
    }

    @Test
    void aFinishedJobIsStoredWithItsReportIntact() {
        assertThat(store).isInstanceOf(JdbcBenchmarkJobStore.class);

        String id = simulation.submitBenchmark(1, 5, 300, EnumSet.allOf(BenchmarkReport.IncidentKind.class),
                BenchmarkReport.RankingMetric.WAIT_PER_BOARDING, 0.05, 0.1).id();
        await().atMost(Duration.ofSeconds(60)).until(() -> store.find(id).isPresent());

        BenchmarkJobs.Job<BenchmarkReport> stored = store.find(id).orElseThrow();
        assertThat(stored.status()).isEqualTo(BenchmarkJobs.Status.SUCCEEDED);
        assertThat(stored.report()).isEqualTo(simulation.benchmarkJob(id).orElseThrow().report());
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(report -> 'rankings') FROM benchmark_job"
                + " WHERE id = ?", Integer.class, id)).isEqualTo(stored.report().rankings().size());
    }

    @Test
    void savingAgainReplacesTheStoredJobAndListsNewestFirst() {
        // PostgreSQL keeps microseconds; truncate so the round trip compares equal.
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS).plus(Duration.ofDays(1));
        String older = UUID.randomUUID().toString();
        String newer = UUID.randomUUID().toString();
        store.save(new BenchmarkJobs.Job<>(older, BenchmarkJobs.Status.CANCELLED, 3, 9, t0, t0, t0.plusSeconds(4),
                null, null));
        BenchmarkJobs.Job<BenchmarkReport> failed = new BenchmarkJobs.Job<>(newer, BenchmarkJobs.Status.FAILED, 1, 9,
                t0.plusSeconds(60), t0.plusSeconds(60), t0.plusSeconds(61), null, "Unknown train: NOPE");
        store.save(failed);
        BenchmarkJobs.Job<BenchmarkReport> cancelled = new BenchmarkJobs.Job<>(older, BenchmarkJobs.Status.CANCELLED, 4, 9, t0, t0,
                t0.plusSeconds(5), null, null);
        store.save(cancelled);

        assertThat(store.find(older)).contains(cancelled);
        assertThat(store.recent(2)).containsExactly(failed, cancelled);
        assertThat(store.find("no-such-job")).isEmpty();
    }
}
