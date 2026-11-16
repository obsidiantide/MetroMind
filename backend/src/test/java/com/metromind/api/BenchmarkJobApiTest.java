package com.metromind.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.sim.HeadwayStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class BenchmarkJobApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void acceptsABenchmarkJobAndServesItsReportOnceDone() throws Exception {
        int strategies = HeadwayStrategy.all().size();
        MvcResult accepted = mvc.perform(post("/api/analysis/benchmark/jobs")
                        .param("scenarios", "2").param("seed", "11").param("horizonSeconds", "300"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").isString())
                .andExpect(jsonPath("$.totalRuns").value(strategies * 3))
                .andExpect(jsonPath("$.progress").isNumber())
                .andReturn();
        String id = json.readTree(accepted.getResponse().getContentAsString()).get("id").asText();
        String location = accepted.getResponse().getHeader("Location");
        assertThat(location).endsWith("/api/analysis/benchmark/jobs/" + id);

        JsonNode job = awaitFinished(id);

        assertThat(job.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(job.get("progress").asDouble()).isEqualTo(1.0);
        assertThat(job.get("completedRuns").asInt()).isEqualTo(strategies * 3);
        assertThat(job.at("/report/seed").asLong()).isEqualTo(11);
        assertThat(job.at("/report/scenarios").size()).isEqualTo(2);
        assertThat(job.at("/report/rankings").size()).isEqualTo(strategies);
        mvc.perform(get("/api/analysis/benchmark/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(id)));
    }

    @Test
    void aJobRanksLikeTheSynchronousBenchmarkFromTheSameState() throws Exception {
        // Both start from the live state at request time; pause the clock so it is the same state for both.
        mvc.perform(post("/api/simulation/pause")).andExpect(status().isNoContent());
        try {
            String sync = mvc.perform(post("/api/analysis/benchmark").param("scenarios", "2").param("seed", "3")
                            .param("horizonSeconds", "300"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String id = json.readTree(mvc.perform(post("/api/analysis/benchmark/jobs").param("scenarios", "2")
                            .param("seed", "3").param("horizonSeconds", "300"))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asText();

            assertThat(awaitFinished(id).get("report")).isEqualTo(json.readTree(sync));
        } finally {
            mvc.perform(post("/api/simulation/resume")).andExpect(status().isNoContent());
        }
    }

    @Test
    void anUnknownJobIsNotFound() throws Exception {
        mvc.perform(get("/api/analysis/benchmark/jobs/no-such-job")).andExpect(status().isNotFound());
    }

    @Test
    void invalidParametersAreABadRequest() throws Exception {
        mvc.perform(post("/api/analysis/benchmark/jobs").param("scenarios", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/analysis/benchmark/jobs").param("kinds", "METEOR"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode awaitFinished(String id) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            JsonNode job = json.readTree(mvc.perform(get("/api/analysis/benchmark/jobs/" + id))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            String status = job.get("status").asText();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Benchmark job " + id + " did not finish in time");
    }
}
