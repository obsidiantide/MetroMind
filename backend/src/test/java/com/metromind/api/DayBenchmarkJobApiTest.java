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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class DayBenchmarkJobApiTest {

    private static final String JOBS = "/api/analysis/benchmark/day/jobs";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void acceptsADayBenchmarkJobAndServesTheSameReportAsTheSynchronousOne() throws Exception {
        int strategies = HeadwayStrategy.all().size();
        MvcResult accepted = mvc.perform(post(JOBS).param("startTimes", "07:45,21:00").param("scenarios", "1")
                        .param("seed", "6").param("horizonSeconds", "300").param("kinds", "DELAY"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.totalRuns").value(2 * strategies * 2))
                .andExpect(jsonPath("$.progress").isNumber())
                .andReturn();
        String id = json.readTree(accepted.getResponse().getContentAsString()).get("id").asText();
        assertThat(accepted.getResponse().getHeader("Location")).endsWith(JOBS + "/" + id);

        JsonNode job = awaitFinished(id);

        assertThat(job.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(job.get("progress").asDouble()).isEqualTo(1.0);
        // The day benchmark starts from fresh warmed-up copies of the network, not the live state, so the job
        // reproduces the synchronous report exactly.
        String sync = mvc.perform(post("/api/analysis/benchmark/day").param("startTimes", "07:45,21:00")
                        .param("scenarios", "1").param("seed", "6").param("horizonSeconds", "300")
                        .param("kinds", "DELAY"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(job.get("report")).isEqualTo(json.readTree(sync));
        mvc.perform(get(JOBS)).andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(id)));
    }

    @Test
    void cancelsALargeDayJobBeforeItFinishes() throws Exception {
        String id = json.readTree(mvc.perform(post(JOBS).param("scenarios", "50").param("horizonSeconds", "3600"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asText();

        mvc.perform(delete(JOBS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        JsonNode job = awaitFinished(id);
        assertThat(job.get("report").isNull()).isTrue();
        assertThat(job.get("completedRuns").asInt()).isLessThan(job.get("totalRuns").asInt());
    }

    @Test
    void badStartTimesAreRefusedBeforeAJobIsQueued() throws Exception {
        mvc.perform(post(JOBS).param("startTimes", "00:10").param("scenarios", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("warm-up")));
        mvc.perform(post(JOBS).param("startTimes", "08:00,08:00").param("scenarios", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(JOBS).param("startTimes", "06:00,09:00,12:00,15:00,18:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownDayJobIsNotFound() throws Exception {
        mvc.perform(get(JOBS + "/no-such-job")).andExpect(status().isNotFound());
        mvc.perform(delete(JOBS + "/no-such-job")).andExpect(status().isNotFound());
    }

    private JsonNode awaitFinished(String id) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            JsonNode job = json.readTree(mvc.perform(get(JOBS + "/" + id))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            String status = job.get("status").asText();
            if (status.equals("SUCCEEDED") || status.equals("FAILED") || status.equals("CANCELLED")) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Benchmark job " + id + " did not finish in time");
    }
}
