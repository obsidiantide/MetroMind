package com.metromind.api;

import com.metromind.sim.HeadwayStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class BenchmarkApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void ranksEveryStrategyOverTheRequestedScenarios() throws Exception {
        mvc.perform(post("/api/analysis/benchmark")
                        .param("scenarios", "2").param("seed", "11").param("horizonSeconds", "300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(11))
                .andExpect(jsonPath("$.horizonSeconds").value(300.0))
                .andExpect(jsonPath("$.metric").value("WAIT_PER_BOARDING"))
                .andExpect(jsonPath("$.scenarios.length()").value(2))
                .andExpect(jsonPath("$.scenarios[0].kind").isString())
                .andExpect(jsonPath("$.scenarios[0].seconds").isNumber())
                .andExpect(jsonPath("$.rankings.length()").value(HeadwayStrategy.all().size()))
                .andExpect(jsonPath("$.rankings[0].rank").value(1))
                .andExpect(jsonPath("$.rankings[0].meanWaitSeconds").isNumber())
                .andExpect(jsonPath("$.rankings[0].p95WaitSeconds").isNumber())
                .andExpect(jsonPath("$.rankings[0].waitSecondsPerScenario.length()").value(2));
    }

    @Test
    void drawsOnlyTheRequestedIncidentKinds() throws Exception {
        mvc.perform(post("/api/analysis/benchmark").param("scenarios", "3").param("seed", "4")
                        .param("horizonSeconds", "300").param("kinds", "SECTION_CLOSURE,STATION_CLOSURE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarios[*].kind",
                        everyItem(oneOf("SECTION_CLOSURE", "STATION_CLOSURE"))))
                .andExpect(jsonPath("$.scenarios[*].trainId").isEmpty());
    }

    @Test
    void ranksByTravelTimeWhenAsked() throws Exception {
        mvc.perform(post("/api/analysis/benchmark").param("scenarios", "2").param("seed", "11")
                        .param("horizonSeconds", "300").param("metric", "TRAVEL_TIME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("TRAVEL_TIME"))
                .andExpect(jsonPath("$.rankings[0].meanTravelMinutes").isNumber())
                .andExpect(jsonPath("$.rankings[0].p95TravelMinutes").isNumber());
    }

    @Test
    void variesRunTimesWhenAskedAndRunsToNominalByDefault() throws Exception {
        mvc.perform(post("/api/analysis/benchmark").param("scenarios", "1").param("seed", "3")
                        .param("horizonSeconds", "300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runTimeVariability").value(0.0));
        mvc.perform(post("/api/analysis/benchmark").param("scenarios", "1").param("seed", "3")
                        .param("horizonSeconds", "300").param("runTimeVariability", "0.08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runTimeVariability").value(0.08));
    }

    @Test
    void tooMuchRunTimeVariabilityIsABadRequest() throws Exception {
        mvc.perform(post("/api/analysis/benchmark").param("runTimeVariability", "0.5"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/analysis/benchmark/jobs").param("runTimeVariability", "-0.1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownMetricIsABadRequest() throws Exception {
        mvc.perform(post("/api/analysis/benchmark").param("metric", "VIBES"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownIncidentKindIsABadRequest() throws Exception {
        mvc.perform(post("/api/analysis/benchmark").param("kinds", "METEOR"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooManyScenariosIsABadRequest() throws Exception {
        mvc.perform(post("/api/analysis/benchmark").param("scenarios", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooShortAHorizonIsABadRequest() throws Exception {
        mvc.perform(post("/api/analysis/benchmark").param("horizonSeconds", "10"))
                .andExpect(status().isBadRequest());
    }
}
