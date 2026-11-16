package com.metromind.api;

import com.metromind.sim.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class KpiApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SimulationService simulation;

    @Test
    void reportsClosedMinutesFromTheLiveSimulation() throws Exception {
        // Each tick advances 5 simulated seconds: 60 ticks close at least three minutes.
        for (int i = 0; i < 60; i++) {
            simulation.tick();
        }
        mvc.perform(get("/api/kpis").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].clock", matchesPattern("\\d\\d:\\d\\d")))
                .andExpect(jsonPath("$[0].trainsReporting", greaterThan(0)))
                .andExpect(jsonPath("$[1].minute", greaterThan(0)));
    }

    @Test
    void statusReportsAWatermarkPerTelemetrySource() throws Exception {
        for (int i = 0; i < 30; i++) {
            simulation.tick();
        }
        double simTime = simulation.snapshot().simTime();
        // The standalone feed sends positions and metrics for every tick, so both keep up with the simulator (the
        // scheduler may have ticked again since).
        mvc.perform(get("/api/kpis/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermarks.POSITIONS", greaterThanOrEqualTo(simTime)))
                .andExpect(jsonPath("$.watermarks.METRICS", greaterThanOrEqualTo(simTime)))
                .andExpect(jsonPath("$.openMinutes", lessThanOrEqualTo(2)))
                .andExpect(jsonPath("$.lateSamples").value(0));
    }

    @Test
    void limitOutsideBoundsIsABadRequest() throws Exception {
        mvc.perform(get("/api/kpis").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/kpis").param("limit", "5000")).andExpect(status().isBadRequest());
    }
}
