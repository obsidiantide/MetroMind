package com.metromind.it;

import com.metromind.incident.AnalysisRecord;
import com.metromind.incident.DelayImpactReport;
import com.metromind.incident.IncidentHistory;
import com.metromind.incident.IncidentRecord;
import com.metromind.infra.JdbcIncidentHistory;
import com.metromind.sim.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentHistoryIT extends InfrastructureIT {

    @Autowired
    IncidentHistory history;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SimulationService simulation;

    @Test
    void flywayMigratesTheSchemaOntoPostgis() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                .isGreaterThanOrEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT PostGIS_Lib_Version()", String.class)).startsWith("3.");
    }

    @Test
    void incidentsAreStoredInPostgresAndListedNewestFirst() {
        assertThat(history).isInstanceOf(JdbcIncidentHistory.class);

        IncidentRecord first = history.record(28_800, IncidentRecord.Kind.DELAY, "RED-1", 120, "");
        IncidentRecord second = history.record(28_860, IncidentRecord.Kind.STATION_CLOSURE, "R3", 600,
                "closed for cleaning");

        assertThat(second.id()).isGreaterThan(first.id());
        assertThat(history.recent(2)).containsExactly(second, first);
    }

    @Test
    void analysisReportsSurviveTheJsonbRoundTripUnchanged() {
        DelayImpactReport report = simulation.analyzeDelay("RED-1", 120, 600);

        AnalysisRecord newest = history.recentAnalyses(1).getFirst();

        assertThat(newest.report()).isEqualTo(report);
        assertThat(jdbc.queryForObject("SELECT best_strategy FROM incident_analysis WHERE id = ?", String.class,
                newest.id())).isEqualTo(report.bestStrategy());
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(report -> 'outcomes') FROM incident_analysis"
                + " WHERE id = ?", Integer.class, newest.id())).isEqualTo(report.outcomes().size());
    }
}
