package com.metromind.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.incident.BenchmarkJobStore;
import com.metromind.incident.BenchmarkJobs;
import com.metromind.incident.BenchmarkReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Finished benchmark jobs in PostgreSQL ({@code benchmark_job}), the report kept whole as JSONB. */
@Component
@ConditionalOnProperty(name = "metromind.persistence.enabled", havingValue = "true")
public class JdbcBenchmarkJobStore implements BenchmarkJobStore {

    private static final String COLUMNS =
            "id, status, completed_runs, total_runs, submitted_at, started_at, finished_at, report, error";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<BenchmarkJobs.Job> rows;

    public JdbcBenchmarkJobStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rows = (rs, i) -> new BenchmarkJobs.Job(rs.getString("id"),
                BenchmarkJobs.Status.valueOf(rs.getString("status")), rs.getInt("completed_runs"),
                rs.getInt("total_runs"), instant(rs.getTimestamp("submitted_at")),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                fromJson(rs.getString("report")), rs.getString("error"));
    }

    @Override
    public void save(BenchmarkJobs.Job job) {
        jdbc.update("""
                INSERT INTO benchmark_job (%s) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, completed_runs = EXCLUDED.completed_runs,
                    total_runs = EXCLUDED.total_runs, started_at = EXCLUDED.started_at,
                    finished_at = EXCLUDED.finished_at, report = EXCLUDED.report, error = EXCLUDED.error
                """.formatted(COLUMNS), job.id(), job.status().name(), job.completedRuns(), job.totalRuns(),
                timestamp(job.submittedAt()), timestamp(job.startedAt()), timestamp(job.finishedAt()),
                toJson(job.report()), job.error());
    }

    @Override
    public Optional<BenchmarkJobs.Job> find(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM benchmark_job WHERE id = ?", rows, id).stream().findFirst();
    }

    @Override
    public List<BenchmarkJobs.Job> recent(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM benchmark_job ORDER BY submitted_at DESC, id LIMIT ?", rows,
                limit);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String toJson(BenchmarkReport report) {
        if (report == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise benchmark report", e);
        }
    }

    private BenchmarkReport fromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, BenchmarkReport.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read stored benchmark report", e);
        }
    }
}
