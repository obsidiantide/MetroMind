package com.metromind.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class IncidentApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    com.metromind.sim.SimulationService simulation;

    @Test
    void closesATrackSection() throws Exception {
        mvc.perform(post("/api/incidents/section-closure").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lineId": "RED", "fromStationId": "R6", "toStationId": "R5", "seconds": 120,
                                 "response": "TURN_BACK"}"""))
                .andExpect(status().isAccepted());
        simulation.tick();
        mvc.perform(get("/api/state"))
                .andExpect(jsonPath("$.closedSections[*].fromStationId", hasItem("R5")))
                .andExpect(jsonPath("$.closedSections[*].response", hasItem("TURN_BACK")))
                .andExpect(jsonPath("$.recentEvents[*].message", hasItem(containsString("closed for 120 s"))));
    }

    @Test
    void stationsThatAreNotNeighboursAreABadRequest() throws Exception {
        mvc.perform(post("/api/incidents/section-closure").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lineId": "RED", "fromStationId": "R1", "toStationId": "R3", "seconds": 60}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("not adjacent")));
    }

    @Test
    void missingLineOrUnknownResponseIsABadRequest() throws Exception {
        mvc.perform(post("/api/incidents/section-closure").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromStationId": "R1", "toStationId": "R2", "seconds": 60}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/incidents/section-closure").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lineId": "RED", "fromStationId": "R1", "toStationId": "R2", "seconds": 60,
                                 "response": "TELEPORT"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void closesAStation() throws Exception {
        mvc.perform(post("/api/incidents/station-closure").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stationId": "G5", "seconds": 120}"""))
                .andExpect(status().isAccepted());
        simulation.tick();
        mvc.perform(get("/api/state"))
                .andExpect(jsonPath("$.stations[?(@.id == 'G5')].closed", hasItem(true)))
                .andExpect(jsonPath("$.stations[?(@.id == 'G5')].waiting", hasItem(0.0)));
        mvc.perform(get("/api/stations/G5/departures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void closingAnUnknownStationIsABadRequest() throws Exception {
        mvc.perform(post("/api/incidents/station-closure").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stationId": "NOPE", "seconds": 60}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown station NOPE"));
        mvc.perform(post("/api/incidents/station-closure").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stationId": "G5", "seconds": 0}"""))
                .andExpect(status().isBadRequest());
    }
}
