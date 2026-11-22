package com.metromind.api;

import com.metromind.sim.HeadwayStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class TimeOfDayBenchmarkApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void ranksStrategiesOverTheRequestedTimesOfDay() throws Exception {
        mvc.perform(post("/api/analysis/benchmark/day").param("startTimes", "07:45,21:00")
                        .param("scenarios", "1").param("seed", "6").param("horizonSeconds", "300")
                        .param("kinds", "DELAY").param("dwellVariability", "0.1")
                        .param("reliefOverrunShare", "0.3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(6))
                .andExpect(jsonPath("$.metric").value("WAIT_PER_BOARDING"))
                .andExpect(jsonPath("$.periods.length()").value(2))
                .andExpect(jsonPath("$.periods[0].startClock").value("07:45"))
                .andExpect(jsonPath("$.periods[1].startClock").value("21:00"))
                .andExpect(jsonPath("$.periods[1].report.scenarios[0].kind").value("DELAY"))
                .andExpect(jsonPath("$.periods[1].report.dwellVariability").value(0.1))
                .andExpect(jsonPath("$.periods[1].report.reliefOverrunShare").value(0.3))
                .andExpect(jsonPath("$.rankings.length()").value(HeadwayStrategy.all().size()))
                .andExpect(jsonPath("$.rankings[0].rank").value(1))
                .andExpect(jsonPath("$.rankings[0].meanWaitSeconds").isNumber())
                .andExpect(jsonPath("$.rankings[0].worstStartClock").value(oneOf("07:45", "21:00")));
    }

    @Test
    void badStartTimesAreABadRequest() throws Exception {
        mvc.perform(post("/api/analysis/benchmark/day").param("startTimes", "00:10").param("scenarios", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("warm-up")));
        mvc.perform(post("/api/analysis/benchmark/day").param("startTimes", "7pm").param("scenarios", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/analysis/benchmark/day").param("startTimes", "06:00,09:00,12:00,15:00,18:00"))
                .andExpect(status().isBadRequest());
    }
}
