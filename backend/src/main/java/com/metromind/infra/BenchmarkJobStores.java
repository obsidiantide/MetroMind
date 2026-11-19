package com.metromind.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.incident.BenchmarkJobStore;
import com.metromind.incident.BenchmarkReport;
import com.metromind.incident.TimeOfDayReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL stores of finished benchmark jobs, one table per kind of report. */
@Configuration
@ConditionalOnProperty(name = "metromind.persistence.enabled", havingValue = "true")
public class BenchmarkJobStores {

    /** Strategy benchmark jobs ({@code benchmark_job}). */
    @Bean
    public BenchmarkJobStore<BenchmarkReport> strategyBenchmarkJobStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcBenchmarkJobStore<>(jdbc, mapper, "benchmark_job", BenchmarkReport.class);
    }

    /** Benchmark jobs across times of day ({@code day_benchmark_job}). */
    @Bean
    public BenchmarkJobStore<TimeOfDayReport> dayBenchmarkJobStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcBenchmarkJobStore<>(jdbc, mapper, "day_benchmark_job", TimeOfDayReport.class);
    }
}
