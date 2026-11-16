package com.metromind.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class OpenApiDocsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void describesTheRestApi() throws Exception {
        mvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("MetroMind API"))
                .andExpect(jsonPath("$.paths['/api/state'].get.summary").isString())
                .andExpect(jsonPath("$.paths['/api/incidents/delay'].post.responses['202']").exists())
                .andExpect(jsonPath("$.paths['/api/analysis/benchmark'].post.parameters[*].name",
                        hasItems("scenarios", "seed", "horizonSeconds", "kinds", "metric")))
                .andExpect(jsonPath("$.paths['/api/stations/nearest'].get.tags[0]").value("Network"))
                .andExpect(jsonPath("$.paths['/api/replay/range'].get.responses['200']").exists())
                .andExpect(jsonPath("$.tags[*].name", hasItems("Simulation", "Incidents", "Analysis", "Network",
                        "KPIs", "Replay")));
    }

    @Test
    void leavesActuatorOutOfTheSpec() throws Exception {
        mvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/actuator/health']").doesNotExist());
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
