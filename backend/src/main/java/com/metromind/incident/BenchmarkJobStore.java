package com.metromind.incident;

import java.util.List;
import java.util.Optional;

/**
 * Durable home of finished benchmark jobs, so their reports outlive a restart. {@link BenchmarkJobs} saves a job
 * once it has succeeded, failed or been cancelled, and falls back to the store for jobs it no longer holds.
 *
 * @param <R> the report a finished job keeps
 */
public interface BenchmarkJobStore<R> {

    /** Keeps nothing: jobs live only as long as {@link BenchmarkJobs} retains them. */
    static <R> BenchmarkJobStore<R> none() {
        return new BenchmarkJobStore<>() {
            @Override
            public void save(BenchmarkJobs.Job<R> job) {
            }

            @Override
            public Optional<BenchmarkJobs.Job<R>> find(String id) {
                return Optional.empty();
            }

            @Override
            public List<BenchmarkJobs.Job<R>> recent(int limit) {
                return List.of();
            }
        };
    }

    /** Stores a finished job, replacing any earlier copy with the same id. */
    void save(BenchmarkJobs.Job<R> job);

    Optional<BenchmarkJobs.Job<R>> find(String id);

    /** Up to {@code limit} stored jobs, most recently submitted first. */
    List<BenchmarkJobs.Job<R>> recent(int limit);
}
