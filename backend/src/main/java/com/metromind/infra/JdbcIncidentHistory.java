package com.metromind.infra;

import com.metromind.incident.IncidentHistory;
import com.metromind.incident.IncidentRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Incident history stored in the PostgreSQL {@code incident} table, so it survives restarts. */
@Component
@ConditionalOnProperty(name = "metromind.persistence.enabled", havingValue = "true")
public class JdbcIncidentHistory implements IncidentHistory {

    private final JdbcTemplate jdbc;

    public JdbcIncidentHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
}
