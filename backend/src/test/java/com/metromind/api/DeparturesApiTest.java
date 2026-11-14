package com.metromind.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class DeparturesApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listsDeparturesFromAnInterchangeOnEveryLine() throws Exception {
        // City Hall (B4) is served by the blue and green lines.
        mvc.perform(get("/api/stations/B4/departures").param("horizonSeconds", "3600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].lineId", hasItem("BLUE")))
                .andExpect(jsonPath("$[*].lineId", hasItem("GREEN")))
                .andExpect(jsonPath("$[*].lineId", everyItem(not(startsWith("RED")))))
                .andExpect(jsonPath("$[0].plannedClock").isString())
                .andExpect(jsonPath("$[0].delaySeconds").isNumber());
    }

    @Test
    void unknownStationIsABadRequest() throws Exception {
        mvc.perform(get("/api/stations/NOPE/departures"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown station NOPE"));
    }

    @Test
    void horizonOutsideLimitsIsABadRequest() throws Exception {
        mvc.perform(get("/api/stations/R3/departures").param("horizonSeconds", "10"))
                .andExpect(status().isBadRequest());
    }
}
