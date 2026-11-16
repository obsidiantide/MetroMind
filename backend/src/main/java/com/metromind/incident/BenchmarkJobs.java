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
 * <p>Jobs are queued, not refused, while others run: give the executor as many threads as benchmarks may run at
 * once. The benchmark itself stays deterministic; only job ids and timestamps vary between runs.
 */
public final class BenchmarkJobs {

    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }

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
            return status == Status.SUCCEEDED || status == Status.FAILED;
        }
    }

    /** Thrown by {@link #submit} when no more jobs can be taken for now. */
    public static final class QueueFullException extends IllegalStateException {
        QueueFullException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ExecutorService executor;
    private final int retained;
    private final Clock clock;
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    public BenchmarkJobs(ExecutorService executor, int retained, Clock clock) {
        if (retained < 1) {
            throw new IllegalArgumentException("At least one benchmark job must be retained");
        }
        this.executor = executor;
        this.retained = retained;
        this.clock = clock;
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
        synchronized (jobs) {
            return Optional.ofNullable(jobs.get(id));
        }
    }

    /** Up to {@code limit} jobs, most recently submitted first. */
    public List<Job> recent(int limit) {
        synchronized (jobs) {
            List<Job> all = new ArrayList<>(jobs.values());
            all.sort(Comparator.comparing(Job::submittedAt).reversed());
            return List.copyOf(all.subList(0, Math.min(limit, all.size())));
        }
    }

    private void run(String id, Function<StrategyBenchmark.ProgressListener, BenchmarkReport> work) {
        update(id, job -> new Job(id, Status.RUNNING, 0, job.totalRuns(), job.submittedAt(), clock.instant(),
                null, null, null));
        try {
            BenchmarkReport report = work.apply((done, total) -> update(id, job -> new Job(id, Status.RUNNING, done,
                    total, job.submittedAt(), job.startedAt(), null, null, null)));
            update(id, job -> new Job(id, Status.SUCCEEDED, job.totalRuns(), job.totalRuns(), job.submittedAt(),
                    job.startedAt(), clock.instant(), report, null));
        } catch (RuntimeException e) {
            update(id, job -> new Job(id, Status.FAILED, job.completedRuns(), job.totalRuns(), job.submittedAt(),
                    job.startedAt(), clock.instant(), null,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void update(String id, UnaryOperator<Job> change) {
        synchronized (jobs) {
            jobs.computeIfPresent(id, (key, job) -> change.apply(job));
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
