-- Finished benchmark jobs across times of day, laid out like benchmark_job; report holds a TimeOfDayReport.
CREATE TABLE day_benchmark_job (
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

CREATE INDEX day_benchmark_job_submitted_at ON day_benchmark_job (submitted_at DESC);
