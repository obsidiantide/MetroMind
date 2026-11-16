-- Finished strategy benchmark jobs (succeeded, failed or cancelled), so their reports survive restarts.
CREATE TABLE benchmark_job (
    id             VARCHAR(36) PRIMARY KEY,
    status         VARCHAR(16) NOT NULL,
    completed_runs INTEGER     NOT NULL,
    total_runs     INTEGER     NOT NULL,
    submitted_at   TIMESTAMPTZ NOT NULL,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ NOT NULL,
    report         JSONB,
    error          TEXT
);

CREATE INDEX benchmark_job_submitted_at ON benchmark_job (submitted_at DESC);
