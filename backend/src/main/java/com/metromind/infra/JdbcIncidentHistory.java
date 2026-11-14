package com.metromind.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.incident.AnalysisRecord;
import com.metromind.incident.DelayImpactReport;
import com.metromind.incident.IncidentHistory;
import com.metromind.incident.IncidentRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Incident history stored in PostgreSQL ({@code incident} and {@code incident_analysis} tables), so it survives
 * restarts. Analysis reports are kept whole as JSONB.
 */
@Component
@ConditionalOnProperty(name = "metromind.persistence.enabled", havingValue = "true")
public class JdbcIncidentHistory implements IncidentHistory {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcIncidentHistory(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public IncidentRecord record(double simTime, IncidentRecord.Kind kind, String subjectId, double seconds,
                                 String detail) {
        Long id = jdbc.queryForObject("""
                INSERT INTO incident (sim_time, kind, subject_id, seconds, detail)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, simTime, kind.name(), subjectId, seconds, detail);
        return new IncidentRecord(id, simTime, kind, subjectId, seconds, detail);
    }

    @Override
    public List<IncidentRecord> recent(int limit) {
        return jdbc.query("""
                SELECT id, sim_time, kind, subject_id, seconds, detail FROM incident ORDER BY id DESC LIMIT ?
                """, (rs, i) -> new IncidentRecord(rs.getLong("id"), rs.getDouble("sim_time"),
                IncidentRecord.Kind.valueOf(rs.getString("kind")), rs.getString("subject_id"),
                rs.getDouble("seconds"), rs.getString("detail")), limit);
    }

    @Override
    public AnalysisRecord recordAnalysis(double simTime, DelayImpactReport report) {
        Long id = jdbc.queryForObject("""
                INSERT INTO incident_analysis (sim_time, train_id, delay_seconds, horizon_seconds, best_strategy,
                                               report)
                VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                """, Long.class, simTime, report.trainId(), report.injectedDelaySeconds(), report.horizonSeconds(),
                report.bestStrategy(), toJson(report));
        return new AnalysisRecord(id, simTime, report);
    }

    @Override
    public List<AnalysisRecord> recentAnalyses(int limit) {
        return jdbc.query("SELECT id, sim_time, report FROM incident_analysis ORDER BY id DESC LIMIT ?",
                (rs, i) -> new AnalysisRecord(rs.getLong("id"), rs.getDouble("sim_time"),
                        fromJson(rs.getString("report"))), limit);
    }

    private String toJson(DelayImpactReport report) {
        try {
            return mapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise analysis report", e);
        }
    }

    private DelayImpactReport fromJson(String json) {
        try {
            return mapper.readValue(json, DelayImpactReport.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read stored analysis report", e);
        }
    }
}
