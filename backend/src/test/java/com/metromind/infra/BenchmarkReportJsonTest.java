package com.metromind.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.incident.BenchmarkReport;
import com.metromind.incident.StrategyBenchmark;
import com.metromind.incident.TimeOfDayBenchmark;
import com.metromind.incident.TimeOfDayReport;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.TestNetworks;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

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

    @Test
    void aTimeOfDayReportSurvivesTheJsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TimeOfDayReport report = new TimeOfDayBenchmark().run(TestNetworks.singleLine(), List.of("08:00", "21:30"),
                2, 7, 600, HeadwayStrategy.all(), EnumSet.of(BenchmarkReport.IncidentKind.DELAY),
                BenchmarkReport.RankingMetric.WAIT_PER_BOARDING, 0.05, 0.1);

        String json = mapper.writeValueAsString(report);

        assertThat(mapper.readValue(json, TimeOfDayReport.class)).isEqualTo(report);
    }

    @Test
    void aReportStoredBeforeTrunkRegularityExistedReadsBackWithNoTrunks() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = """
                {"seed":1,"horizonSeconds":600,"metric":"WAIT_PER_BOARDING","scenarios":[],
                 "rankings":[{"rank":1,"strategy":"BALANCED","meanWaitSeconds":60,"p95WaitSeconds":70,
                              "meanTravelMinutes":500,"p95TravelMinutes":550,"meanRecoverySeconds":null,
                              "unrecoveredScenarios":0,"meanShortTurns":0,"shortTurnsByStation":{},
                              "meanStopsSkipped":0,"meanPassengersCarriedPast":0,"stopsSkippedByStation":{},
                              "waitSecondsPerScenario":[]}]}
                """;

        BenchmarkReport report = mapper.readValue(json, BenchmarkReport.class);

        assertThat(report.rankings().get(0).meanTrunkHeadwayCv()).isEmpty();
    }
}
