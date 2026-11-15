package com.metromind.infra;

import com.metromind.kpi.KpiStore;
import com.metromind.kpi.MinuteKpi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Per-minute KPIs in PostgreSQL ({@code minute_kpi}); a re-aggregated minute overwrites the stored one. */
@Component
@ConditionalOnProperty(name = "metromind.persistence.enabled", havingValue = "true")
public class JdbcKpiStore implements KpiStore {

    private final JdbcTemplate jdbc;

    public JdbcKpiStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(MinuteKpi k) {
        jdbc.update("""
                INSERT INTO minute_kpi (minute, clock, trains_reporting, average_delay_seconds, max_delay_seconds,
                                        average_load_factor, average_waiting, max_waiting, average_headway_cv,
                                        delay_alerts, incidents)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (minute) DO UPDATE SET clock = EXCLUDED.clock,
                    trains_reporting = EXCLUDED.trains_reporting,
                    average_delay_seconds = EXCLUDED.average_delay_seconds,
                    max_delay_seconds = EXCLUDED.max_delay_seconds,
                    average_load_factor = EXCLUDED.average_load_factor,
                    average_waiting = EXCLUDED.average_waiting, max_waiting = EXCLUDED.max_waiting,
                    average_headway_cv = EXCLUDED.average_headway_cv, delay_alerts = EXCLUDED.delay_alerts,
                    incidents = EXCLUDED.incidents, updated_at = now()
                """, k.minute(), k.clock(), k.trainsReporting(), k.averageDelaySeconds(), k.maxDelaySeconds(),
                k.averageLoadFactor(), k.averageWaiting(), k.maxWaiting(), k.averageHeadwayCv(), k.delayAlerts(),
                k.incidents());
    }

    @Override
    public List<MinuteKpi> recent(int limit) {
        return jdbc.query("""
                SELECT * FROM (SELECT * FROM minute_kpi ORDER BY minute DESC LIMIT ?) newest ORDER BY minute
                """, (rs, i) -> new MinuteKpi(rs.getLong("minute"), rs.getString("clock"),
                rs.getInt("trains_reporting"), rs.getDouble("average_delay_seconds"),
                rs.getDouble("max_delay_seconds"), rs.getDouble("average_load_factor"),
                rs.getDouble("average_waiting"), rs.getDouble("max_waiting"), rs.getDouble("average_headway_cv"),
                rs.getInt("delay_alerts"), rs.getInt("incidents")), limit);
    }
}
