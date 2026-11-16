package com.metromind.incident;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
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
        BenchmarkJobs jobs = new BenchmarkJobs(executor, 5, clock);
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<BenchmarkJobs.Job> seenWhileRunning = new AtomicReference<>();
        BenchmarkJobs.Job queued = jobs.submit(4, progress -> {
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
        BenchmarkJobs.Job done = jobs.get(queued.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(BenchmarkJobs.Status.SUCCEEDED);
        assertThat(done.progress()).isEqualTo(1.0);
        assertThat(done.report()).isEqualTo(REPORT);
        assertThat(done.finishedAt()).isEqualTo(clock.instant());
        assertThat(done.error()).isNull();
    }

    @Test
    void aFailingBenchmarkMarksTheJobFailedWithItsMessage() {
        BenchmarkJobs jobs = new BenchmarkJobs(executor, 5, clock);
        String id = jobs.submit(3, progress -> {
            progress.onProgress(1, 3);
            throw new IllegalArgumentException("Unknown train: NOPE");
        }).id();

        executor.runNext();

        BenchmarkJobs.Job failed = jobs.get(id).orElseThrow();
        assertThat(failed.status()).isEqualTo(BenchmarkJobs.Status.FAILED);
        assertThat(failed.error()).isEqualTo("Unknown train: NOPE");
        assertThat(failed.completedRuns()).isEqualTo(1);
        assertThat(failed.report()).isNull();
    }

    @Test
    void forgetsTheOldestFinishedJobsBeyondTheRetainedCount() {
        BenchmarkJobs jobs = new BenchmarkJobs(executor, 2, clock);
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
        BenchmarkJobs jobs = new BenchmarkJobs(executor, 2, clock);
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
        BenchmarkJobs jobs = new BenchmarkJobs(executor, 5, clock);
        AtomicReference<Boolean> started = new AtomicReference<>(false);
        String id = jobs.submit(2, progress -> {
            started.set(true);
            return REPORT;
        }).id();

        BenchmarkJobs.Job cancelled = jobs.cancel(id).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(BenchmarkJobs.Status.CANCELLED);
        assertThat(cancelled.finishedAt()).isEqualTo(clock.instant());
        assertThat(cancelled.startedAt()).isNull();

        executor.runNext();
        assertThat(started.get()).isFalse();
        assertThat(jobs.get(id)).contains(cancelled);
    }

    @Test
    void aCancelledRunningJobStopsAtItsNextProgressReport() {
        BenchmarkJobs jobs = new BenchmarkJobs(executor, 5, clock);
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
        BenchmarkJobs.Job job = jobs.get(id.get()).orElseThrow();
        assertThat(job.status()).isEqualTo(BenchmarkJobs.Status.CANCELLED);
        assertThat(job.completedRuns()).isEqualTo(2);
        assertThat(job.report()).isNull();
        assertThat(job.finished()).isTrue();
    }

    @Test
    void cancellingAFinishedJobLeavesItAsItWas() {
        BenchmarkJobs jobs = new BenchmarkJobs(executor, 5, clock);
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
        BenchmarkJobs jobs = new BenchmarkJobs(executor, 1, clock);
        String id = jobs.submit(1, progress -> REPORT).id();
        jobs.cancel(id);
        assertThat(jobs.submit(1, progress -> REPORT).status()).isEqualTo(BenchmarkJobs.Status.QUEUED);
        assertThat(jobs.get(id)).isEmpty();
    }

    @Test
    void anUnknownJobIsEmpty() {
        assertThat(new BenchmarkJobs(executor, 1, clock).get("missing")).isEmpty();
    }
}
