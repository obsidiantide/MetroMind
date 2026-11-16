package com.metromind.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.incident.BenchmarkReport;
import com.metromind.incident.StrategyBenchmark;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.TestNetworks;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link JdbcBenchmarkJobStore} keeps reports as JSON: a real report must read back exactly as it was written. */
class BenchmarkReportJsonTest {

    @Test
    void aBenchmarkReportSurvivesTheJsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SimulationEngine live = new SimulationEngine(TestNetworks.singleLine(), 8 * 3600, HeadwayStrategy.NO_CONTROL);
        live.run(300, 1);
        // Every incident kind, so each scenario shape (train, section and station incidents) is written once.
        BenchmarkReport report = new StrategyBenchmark().run(live, 6, 7, 900, HeadwayStrategy.all(),
                EnumSet.allOf(BenchmarkReport.IncidentKind.class), BenchmarkReport.RankingMetric.TRAVEL_TIME);

        String json = mapper.writeValueAsString(report);

        assertThat(mapper.readValue(json, BenchmarkReport.class)).isEqualTo(report);
    }
}
