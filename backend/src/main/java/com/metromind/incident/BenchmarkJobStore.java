package com.metromind.incident;

import java.util.List;
import java.util.Optional;

/**
 * Durable home of finished benchmark jobs, so their reports outlive a restart. {@link BenchmarkJobs} saves a job
 * once it has succeeded, failed or been cancelled, and falls back to the store for jobs it no longer holds.
 */
public interface BenchmarkJobStore {

    /** Keeps nothing: jobs live only as long as {@link BenchmarkJobs} retains them. */
    BenchmarkJobStore NONE = new BenchmarkJobStore() {
        @Override
        public void save(BenchmarkJobs.Job job) {
        }

        @Override
        public Optional<BenchmarkJobs.Job> find(String id) {
            return Optional.empty();
        }

        @Override
        public List<BenchmarkJobs.Job> recent(int limit) {
            return List.of();
        }
    };

    /** Stores a finished job, replacing any earlier copy with the same id. */
    void save(BenchmarkJobs.Job job);

    Optional<BenchmarkJobs.Job> find(String id);

    /** Up to {@code limit} stored jobs, most recently submitted first. */
    List<BenchmarkJobs.Job> recent(int limit);
}
