package com.metromind.it;

import com.metromind.incident.IncidentHistory;
import com.metromind.incident.IncidentRecord;
import com.metromind.infra.JdbcIncidentHistory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentHistoryIT extends InfrastructureIT {

    @Autowired
    IncidentHistory history;

    @Autowired
    JdbcTemplate jdbc;

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
}
