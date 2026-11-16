package com.metromind.incident;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Runs strategy benchmarks in the background so a large run does not hold an HTTP request open. A job is queued on
 * the given executor, reports its progress in simulated runs as it goes and keeps its report once done. Only the
 * {@code retained} most recent jobs are kept; when more are submitted the oldest finished ones are forgotten.
 *
 * <p>A queued or running job can be {@linkplain #cancel cancelled}. A queued job never starts; a running one stops
 * at its next progress report, i.e. after the simulated run in progress, and its thread is then free for the next
 * job.
 *
 * <p>Every job that reaches a final state is handed to a {@link BenchmarkJobStore}. Lookups and listings fall back to
 * the store, so finished jobs (and their reports) stay available after they have been forgotten here or after a
 * restart. Jobs still queued or running when the process stops are lost.
 *
 * <p>Jobs are queued, not refused, while others run: give the executor as many threads as benchmarks may run at
 * once. The benchmark itself stays deterministic; only job ids and timestamps vary between runs.
 */
public final class BenchmarkJobs {

    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    /**
     * A benchmark job as seen by a poller. {@code report} is set once it has {@link Status#SUCCEEDED}, and
     * {@code error} once it has {@link Status#FAILED}.
     */
    public record Job(String id, Status status, int completedRuns, int totalRuns, Instant submittedAt,
                      Instant startedAt, Instant finishedAt, BenchmarkReport report, String error) {

        /** Share of the simulated runs done, from 0 to 1. */
        @JsonProperty("progress")
        public double progress() {
            return totalRuns == 0 ? 0 : (double) completedRuns / totalRuns;
        }

        public boolean finished() {
            return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED;
        }
    }

    /** Thrown by {@link #submit} when no more jobs can be taken for now. */
    public static final class QueueFullException extends IllegalStateException {
        QueueFullException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Unwinds a running benchmark whose job was cancelled. */
    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super(null, null, false, false);
        }
    }

    private final ExecutorService executor;
    private final int retained;
    private final Clock clock;
    private final BenchmarkJobStore store;
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    public BenchmarkJobs(ExecutorService executor, int retained, Clock clock) {
        this(executor, retained, clock, BenchmarkJobStore.NONE);
    }

    public BenchmarkJobs(ExecutorService executor, int retained, Clock clock, BenchmarkJobStore store) {
        if (retained < 1) {
            throw new IllegalArgumentException("At least one benchmark job must be retained");
        }
        this.executor = executor;
        this.retained = retained;
        this.clock = clock;
        this.store = store;
    }

    /**
     * Queues {@code work}, which runs the benchmark and reports progress through the listener it is given, and
     * returns the job as queued. {@code totalRuns} is the number of runs the progress counts up to.
     *
     * @throws QueueFullException if the retained jobs are all still queued or running, or the executor refuses
     */
    public Job submit(int totalRuns, Function<StrategyBenchmark.ProgressListener, BenchmarkReport> work) {
        String id = UUID.randomUUID().toString();
        Job queued = new Job(id, Status.QUEUED, 0, totalRuns, clock.instant(), null, null, null, null);
        synchronized (jobs) {
            evictFinished();
            if (jobs.size() >= retained) {
                throw new QueueFullException(
                        "%d benchmark jobs are already queued or running; try again later".formatted(jobs.size()), null);
            }
            jobs.put(id, queued);
        }
        try {
            executor.execute(() -> run(id, work));
        } catch (RejectedExecutionException e) {
            synchronized (jobs) {
                jobs.remove(id);
            }
            throw new QueueFullException("Benchmark jobs are not being accepted: " + e.getMessage(), e);
        }
        return queued;
    }

    public Optional<Job> get(String id) {
        Job held;
        synchronized (jobs) {
            held = jobs.get(id);
        }
        return held != null ? Optional.of(held) : store.find(id);
    }

    /**
     * Cancels a queued or running job and returns it as it now stands: {@link Status#CANCELLED}, or unchanged if it
     * had already succeeded or failed. Cancelling a cancelled job again is harmless. Empty if there is no such job.
     */
    public Optional<Job> cancel(String id) {
        Job before;
        Job after;
        synchronized (jobs) {
            before = jobs.get(id);
            if (before == null) {
                return store.find(id);
            }
            after = before.finished() ? before
                    : new Job(id, Status.CANCELLED, before.completedRuns(), before.totalRuns(), before.submittedAt(),
                            before.startedAt(), clock.instant(), null, null);
            jobs.put(id, after);
        }
        if (after != before) {
            store.save(after);
        }
        return Optional.of(after);
    }

    /** Up to {@code limit} jobs, most recently submitted first. */
    public List<Job> recent(int limit) {
        Map<String, Job> all = new LinkedHashMap<>();
        synchronized (jobs) {
            all.putAll(jobs);
        }
        for (Job stored : store.recent(limit)) {
            all.putIfAbsent(stored.id(), stored);
        }
        List<Job> sorted = new ArrayList<>(all.values());
        sorted.sort(Comparator.comparing(Job::submittedAt).reversed());
        return List.copyOf(sorted.subList(0, Math.min(limit, sorted.size())));
    }

    private void run(String id, Function<StrategyBenchmark.ProgressListener, BenchmarkReport> work) {
        synchronized (jobs) {
            Job job = jobs.get(id);
            if (job == null || job.status() != Status.QUEUED) {
                return; // cancelled (or forgotten) before it started
            }
            jobs.put(id, new Job(id, Status.RUNNING, 0, job.totalRuns(), job.submittedAt(), clock.instant(),
                    null, null, null));
        }
        try {
            BenchmarkReport report = work.apply((done, total) -> {
                if (updateRunning(id, job -> new Job(id, Status.RUNNING, done, total, job.submittedAt(),
                        job.startedAt(), null, null, null)) == null) {
                    throw new Cancelled();
                }
            });
            finish(id, job -> new Job(id, Status.SUCCEEDED, job.totalRuns(), job.totalRuns(),
                    job.submittedAt(), job.startedAt(), clock.instant(), report, null));
        } catch (Cancelled e) {
            // The job was already marked cancelled; stopping early is all that is left to do.
        } catch (RuntimeException e) {
            finish(id, job -> new Job(id, Status.FAILED, job.completedRuns(), job.totalRuns(),
                    job.submittedAt(), job.startedAt(), clock.instant(), null,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** Moves a running job to its final state and stores it, unless it was cancelled meanwhile. */
    private void finish(String id, UnaryOperator<Job> change) {
        Job finished = updateRunning(id, change);
        if (finished != null) {
            store.save(finished);
        }
    }

    /**
     * Applies {@code change} if the job is still running and returns the changed job; null if it was cancelled or
     * forgotten meanwhile.
     */
    private Job updateRunning(String id, UnaryOperator<Job> change) {
        synchronized (jobs) {
            Job job = jobs.get(id);
            if (job == null || job.status() != Status.RUNNING) {
                return null;
            }
            Job changed = change.apply(job);
            jobs.put(id, changed);
            return changed;
        }
    }

    /** Forgets the oldest finished jobs until there is room for one more. */
    private void evictFinished() {
        Iterator<Job> it = jobs.values().iterator();
        while (jobs.size() >= retained && it.hasNext()) {
            if (it.next().finished()) {
                it.remove();
            }
        }
    }
}
