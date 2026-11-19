package com.metromind.incident;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkJobsTest {

    private static final BenchmarkReport REPORT = new BenchmarkReport(1, 600,
            BenchmarkReport.RankingMetric.WAIT_PER_BOARDING, List.of(), List.of());

    /** Holds submitted tasks until the test runs them, so every state of a job can be observed. */
    private static final class ManualExecutor extends AbstractExecutorService {
        final Deque<Runnable> tasks = new ArrayDeque<>();

        void runNext() {
            tasks.removeFirst().run();
        }

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.copyOf(tasks);
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    private final ManualExecutor executor = new ManualExecutor();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void aJobIsQueuedThenRunsWithProgressThenKeepsItsReport() {
        BenchmarkJobs<BenchmarkReport> jobs = new BenchmarkJobs<>(executor, 5, clock);
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<BenchmarkJobs.Job<BenchmarkReport>> seenWhileRunning = new AtomicReference<>();
        BenchmarkJobs.Job<BenchmarkReport> queued = jobs.submit(4, progress -> {
            progress.onProgress(1, 4);
            progress.onProgress(2, 4);
            seenWhileRunning.set(jobs.get(id.get()).orElseThrow());
            return REPORT;
        });
        id.set(queued.id());

        assertThat(queued.status()).isEqualTo(BenchmarkJobs.Status.QUEUED);
        assertThat(queued.progress()).isZero();
        assertThat(jobs.get(queued.id())).contains(queued);

        executor.runNext();

        assertThat(seenWhileRunning.get().status()).isEqualTo(BenchmarkJobs.Status.RUNNING);
        assertThat(seenWhileRunning.get().completedRuns()).isEqualTo(2);
        assertThat(seenWhileRunning.get().progress()).isEqualTo(0.5);
        BenchmarkJobs.Job<BenchmarkReport> done = jobs.get(queued.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(BenchmarkJobs.Status.SUCCEEDED);
        assertThat(done.progress()).isEqualTo(1.0);
        assertThat(done.report()).isEqualTo(REPORT);
        assertThat(done.finishedAt()).isEqualTo(clock.instant());
        assertThat(done.error()).isNull();
    }

    @Test
    void aFailingBenchmarkMarksTheJobFailedWithItsMessage() {
        BenchmarkJobs<BenchmarkReport> jobs = new BenchmarkJobs<>(executor, 5, clock);
        String id = jobs.submit(3, progress -> {
            progress.onProgress(1, 3);
            throw new IllegalArgumentException("Unknown train: NOPE");
        }).id();

        executor.runNext();

        BenchmarkJobs.Job<BenchmarkReport> failed = jobs.get(id).orElseThrow();
        assertThat(failed.status()).isEqualTo(BenchmarkJobs.Status.FAILED);
        assertThat(failed.error()).isEqualTo("Unknown train: NOPE");
        assertThat(failed.completedRuns()).isEqualTo(1);
        assertThat(failed.report()).isNull();
    }

    @Test
    void forgetsTheOldestFinishedJobsBeyondTheRetainedCount() {
        BenchmarkJobs<BenchmarkReport> jobs = new BenchmarkJobs<>(executor, 2, clock);
        String first = jobs.submit(1, progress -> REPORT).id();
        String second = jobs.submit(1, progress -> REPORT).id();
        executor.runNext();
        executor.runNext();

        String third = jobs.submit(1, progress -> REPORT).id();

        assertThat(jobs.get(first)).isEmpty();
        assertThat(jobs.get(second)).isPresent();
        assertThat(jobs.get(third)).isPresent();
        assertThat(jobs.recent(10)).hasSize(2);
    }

    @Test
    void refusesNewJobsWhileEveryRetainedOneIsUnfinished() {
        BenchmarkJobs<BenchmarkReport> jobs = new BenchmarkJobs<>(executor, 2, clock);
        jobs.submit(1, progress -> REPORT);
        jobs.submit(1, progress -> REPORT);

        assertThatThrownBy(() -> jobs.submit(1, progress -> REPORT))
                .isInstanceOf(BenchmarkJobs.QueueFullException.class)
                .hasMessageContaining("already queued or running");

        executor.runNext();
        assertThat(jobs.submit(1, progress -> REPORT).status()).isEqualTo(BenchmarkJobs.Status.QUEUED);
    }

    @Test
    void aCancelledQueuedJobNeverStarts() {
        BenchmarkJobs<BenchmarkReport> jobs = new BenchmarkJobs<>(executor, 5, clock);
        AtomicReference<Boolean> started = new AtomicReference<>(false);
        String id = jobs.submit(2, progress -> {
            started.set(true);
            return REPORT;
        }).id();

        BenchmarkJobs.Job<BenchmarkReport> cancelled = jobs.cancel(id).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(BenchmarkJobs.Status.CANCELLED);
        assertThat(cancelled.finishedAt()).isEqualTo(clock.instant());
        assertThat(cancelled.startedAt()).isNull();

        executor.runNext();
        assertThat(started.get()).isFalse();
        assertThat(jobs.get(id)).contains(cancelled);
    }

    @Test
    void aCancelledRunningJobStopsAtItsNextProgressReport() {
        BenchmarkJobs<BenchmarkReport> jobs = new BenchmarkJobs<>(executor, 5, clock);
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<Integer> lastRun = new AtomicReference<>(0);
        id.set(jobs.submit(4, progress -> {
            for (int run = 1; run <= 4; run++) {
                lastRun.set(run);
                progress.onProgress(run, 4);
                if (run == 2) {
                    jobs.cancel(id.get());
                }
            }
            return REPORT;
        }).id());

        executor.runNext();

        // Cancelled after the second run: the third run is the last one started, and its report is rejected.
        assertThat(lastRun.get()).isEqualTo(3);
        BenchmarkJobs.Job<BenchmarkReport> job = jobs.get(id.get()).orElseThrow();
        assertThat(job.status()).isEqualTo(BenchmarkJobs.Status.CANCELLED);
        assertThat(job.completedRuns()).isEqualTo(2);
        assertThat(job.report()).isNull();
        assertThat(job.finished()).isTrue();
    }

    @Test
    void cancellingAFinishedJobLeavesItAsItWas() {
        BenchmarkJobs<BenchmarkReport> jobs = new BenchmarkJobs<>(executor, 5, clock);
        String id = jobs.submit(1, progress -> REPORT).id();
        executor.runNext();

        assertThat(jobs.cancel(id)).hasValueSatisfying(job -> {
            assertThat(job.status()).isEqualTo(BenchmarkJobs.Status.SUCCEEDED);
            assertThat(job.report()).isEqualTo(REPORT);
        });
        assertThat(jobs.cancel("missing")).isEmpty();
    }

    @Test
    void aCancelledJobFreesItsPlaceForANewOne() {
        BenchmarkJobs<BenchmarkReport> jobs = new BenchmarkJobs<>(executor, 1, clock);
        String id = jobs.submit(1, progress -> REPORT).id();
        jobs.cancel(id);
        assertThat(jobs.submit(1, progress -> REPORT).status()).isEqualTo(BenchmarkJobs.Status.QUEUED);
        assertThat(jobs.get(id)).isEmpty();
    }

    /** A store backed by a map, standing in for PostgreSQL. */
    private static final class MapStore implements BenchmarkJobStore<BenchmarkReport> {
        final Map<String, BenchmarkJobs.Job<BenchmarkReport>> saved = new LinkedHashMap<>();

        @Override
        public void save(BenchmarkJobs.Job<BenchmarkReport> job) {
            saved.put(job.id(), job);
        }

        @Override
        public Optional<BenchmarkJobs.Job<BenchmarkReport>> find(String id) {
            return Optional.ofNullable(saved.get(id));
        }

        @Override
        public List<BenchmarkJobs.Job<BenchmarkReport>> recent(int limit) {
            return saved.values().stream().sorted(Comparator.comparing(BenchmarkJobs.Job<BenchmarkReport>::submittedAt).reversed())
                    .limit(limit).toList();
        }
    }

    @Test
    void storesOnlyJobsThatReachAFinalState() {
        MapStore store = new MapStore();
        BenchmarkJobs<BenchmarkReport> jobs = new BenchmarkJobs<>(executor, 5, clock, store);
        String ok = jobs.submit(1, progress -> REPORT).id();
        String failed = jobs.submit(1, progress -> {
            throw new IllegalStateException("boom");
        }).id();
        String cancelled = jobs.submit(1, progress -> REPORT).id();
        String queued = jobs.submit(1, progress -> REPORT).id();
        assertThat(store.saved).isEmpty();

        executor.runNext();
        executor.runNext();
        jobs.cancel(cancelled);

        assertThat(store.saved).containsOnlyKeys(ok, failed, cancelled);
        assertThat(store.saved.get(ok).report()).isEqualTo(REPORT);
        assertThat(store.saved.get(failed).status()).isEqualTo(BenchmarkJobs.Status.FAILED);
        assertThat(store.saved.get(cancelled).status()).isEqualTo(BenchmarkJobs.Status.CANCELLED);
        assertThat(store.saved).doesNotContainKey(queued);
    }

    @Test
    void finishedJobsOutliveARestartThroughTheStore() {
        MapStore store = new MapStore();
        BenchmarkJobs<BenchmarkReport> before = new BenchmarkJobs<>(executor, 5, clock, store);
        String id = before.submit(2, progress -> REPORT).id();
        executor.runNext();

        BenchmarkJobs<BenchmarkReport> after = new BenchmarkJobs<>(new ManualExecutor(), 5, clock, store);
        assertThat(after.get(id)).hasValueSatisfying(job -> assertThat(job.report()).isEqualTo(REPORT));
        assertThat(after.recent(10)).extracting(BenchmarkJobs.Job::id).containsExactly(id);
        // Cancelling a stored job that has finished leaves it as it was.
        assertThat(after.cancel(id)).hasValueSatisfying(job ->
                assertThat(job.status()).isEqualTo(BenchmarkJobs.Status.SUCCEEDED));
    }

    @Test
    void listingsMergeHeldAndStoredJobsWithoutDuplicates() {
        MapStore store = new MapStore();
        Clock later = Clock.offset(clock, Duration.ofMinutes(5));
        BenchmarkJobs<BenchmarkReport> jobs = new BenchmarkJobs<>(executor, 1, clock, store);
        String old = jobs.submit(1, progress -> REPORT).id();
        executor.runNext();
        BenchmarkJobs<BenchmarkReport> restarted = new BenchmarkJobs<>(executor, 1, later, store);
        String fresh = restarted.submit(1, progress -> REPORT).id();
        executor.runNext();

        // Both held and stored now; the newer appears once, first.
        assertThat(restarted.recent(10)).extracting(BenchmarkJobs.Job::id).containsExactly(fresh, old);
        assertThat(restarted.recent(1)).extracting(BenchmarkJobs.Job::id).containsExactly(fresh);
    }

    @Test
    void anUnknownJobIsEmpty() {
        assertThat(new BenchmarkJobs<>(executor, 1, clock).get("missing")).isEmpty();
    }
}
