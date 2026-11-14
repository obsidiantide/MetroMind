-- Results of what-if (delay-impact) analyses. The summary columns allow querying; the full report is kept as JSON.
CREATE TABLE incident_analysis (
    id              BIGSERIAL PRIMARY KEY,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sim_time        DOUBLE PRECISION NOT NULL,
    train_id        VARCHAR(32) NOT NULL,
    delay_seconds   DOUBLE PRECISION NOT NULL,
    horizon_seconds DOUBLE PRECISION NOT NULL,
    best_strategy   VARCHAR(32),
    report          JSONB NOT NULL
);
