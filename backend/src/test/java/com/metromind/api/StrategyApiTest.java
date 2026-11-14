package com.metromind.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class StrategyApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    com.metromind.sim.SimulationService simulation;

    @AfterEach
    void restoreDefaultStrategy() throws Exception {
        selectStrategy("NO_CONTROL");
    }

    @Test
    void offersAdaptiveDispatching() throws Exception {
        mvc.perform(get("/api/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("ADAPTIVE_DISPATCH")));
    }

    @Test
    void selectsAdaptiveDispatchingAndReportsTheFleet() throws Exception {
        selectStrategy("ADAPTIVE_DISPATCH");
        simulation.tick();

        // The seed network runs 15 trains with 3 spares in the depots, wherever they are at the moment.
        mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("ADAPTIVE_DISPATCH"))
                .andExpect(jsonPath("$.metrics.trainsInService").isNumber())
                .andExpect(jsonPath("$.metrics.trainsInDepot").isNumber())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    int inService = com.jayway.jsonpath.JsonPath.read(body, "$.metrics.trainsInService");
                    int inDepot = com.jayway.jsonpath.JsonPath.read(body, "$.metrics.trainsInDepot");
                    org.assertj.core.api.Assertions.assertThat(inService + inDepot).isEqualTo(18);
                    org.assertj.core.api.Assertions.assertThat(inService).isBetween(15, 18);
                });
    }

    @Test
    void offersAndSelectsShortTurning() throws Exception {
        mvc.perform(get("/api/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("SHORT_TURN")));
        selectStrategy("SHORT_TURN");
        simulation.tick();

        mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("SHORT_TURN"));
    }

    @Test
    void rejectsAnUnknownStrategy() throws Exception {
        mvc.perform(put("/api/strategy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\": \"SKIP_EVERYTHING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown strategy SKIP_EVERYTHING"));
    }

    private void selectStrategy(String name) throws Exception {
        mvc.perform(put("/api/strategy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\": \"" + name + "\"}"))
                .andExpect(status().is2xxSuccessful());
    }
}
