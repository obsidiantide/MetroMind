-- Per-minute KPIs aggregated from the Kafka telemetry stream, keyed by minute of simulated time.
CREATE TABLE minute_kpi (
    minute                BIGINT PRIMARY KEY,
    clock                 VARCHAR(5) NOT NULL,
    trains_reporting      INT NOT NULL,
    average_delay_seconds DOUBLE PRECISION NOT NULL,
    max_delay_seconds     DOUBLE PRECISION NOT NULL,
    average_load_factor   DOUBLE PRECISION NOT NULL,
    average_waiting       DOUBLE PRECISION NOT NULL,
    max_waiting           DOUBLE PRECISION NOT NULL,
    average_headway_cv    DOUBLE PRECISION NOT NULL,
    delay_alerts          INT NOT NULL,
    incidents             INT NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
