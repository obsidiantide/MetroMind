package com.metromind.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.sim.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class ReplayApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SimulationService simulation;

    @Autowired
    ObjectMapper mapper;

    @Test
    void replaysAnEarlierSnapshotOfTheLiveSimulation() throws Exception {
        for (int i = 0; i < 20; i++) {
            simulation.tick();
        }
        JsonNode range = mapper.readTree(mvc.perform(get("/api/replay/range"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        double from = range.get("fromSimTime").asDouble();
        double to = range.get("toSimTime").asDouble();
        assertThat(to).isGreaterThan(from);
        assertThat(range.get("snapshots").asInt()).isGreaterThan(1);

        mvc.perform(get("/api/replay").param("at", String.valueOf(from + 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simTime").value(from))
                .andExpect(jsonPath("$.trains").isNotEmpty());
    }

    @Test
    void timeBeforeTheKeptRangeIsNotFound() throws Exception {
        simulation.tick();
        mvc.perform(get("/api/replay").param("at", "0"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("/api/replay/range")));
    }
}
