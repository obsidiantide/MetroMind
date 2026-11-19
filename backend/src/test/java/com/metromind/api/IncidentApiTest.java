package com.metromind.api;

import com.metromind.sim.HeadwayStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
    void holdsATrainWithADoorFaultAndRecordsIt() throws Exception {
        mvc.perform(post("/api/incidents/door-fault").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trainId": "GREEN-2", "seconds": 600}"""))
                .andExpect(status().isAccepted());
        simulation.tick();
        mvc.perform(get("/api/state"))
                .andExpect(jsonPath("$.activeIncidents[?(@.subjectId == 'GREEN-2')].kind", hasItem("DOOR_FAULT")));
        mvc.perform(get("/api/incidents").param("limit", "1"))
                .andExpect(jsonPath("$[0].kind").value("DOOR_FAULT"))
                .andExpect(jsonPath("$[0].subjectId").value("GREEN-2"))
                .andExpect(jsonPath("$[0].seconds").value(600.0));
    }

    @Test
    void aDoorFaultOnAnUnknownTrainOrWithoutSecondsIsABadRequest() throws Exception {
        mvc.perform(post("/api/incidents/door-fault").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trainId": "NOPE-1", "seconds": 60}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/incidents/door-fault").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trainId": "GREEN-2", "seconds": 0}"""))
                .andExpect(status().isBadRequest());
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

    @Test
    void listsInjectedIncidentsNewestFirst() throws Exception {
        mvc.perform(post("/api/incidents/delay").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trainId": "RED-1", "seconds": 30}"""))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/incidents/section-closure").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lineId": "BLUE", "fromStationId": "B1", "toStationId": "B2", "seconds": 60}"""))
                .andExpect(status().isAccepted());

        mvc.perform(get("/api/incidents").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("SECTION_CLOSURE"))
                .andExpect(jsonPath("$[0].subjectId").value("BLUE"))
                .andExpect(jsonPath("$[0].detail").value("B1-B2, WAIT"))
                .andExpect(jsonPath("$[1].kind").value("DELAY"))
                .andExpect(jsonPath("$[1].subjectId").value("RED-1"))
                .andExpect(jsonPath("$[1].seconds").value(30.0));
    }

    @Test
    void rejectedIncidentsAreNotRecorded() throws Exception {
        mvc.perform(post("/api/incidents/station-closure").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stationId": "NOPE", "seconds": 60}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/incidents"))
                .andExpect(jsonPath("$[*].subjectId", not(hasItem("NOPE"))));
    }

    @Test
    void historyLimitIsValidated() throws Exception {
        mvc.perform(get("/api/incidents").param("limit", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void keepsTheResultOfEachWhatIfAnalysis() throws Exception {
        mvc.perform(post("/api/analysis/delay-impact").param("horizonSeconds", "300")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trainId": "GREEN-1", "seconds": 90}"""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/incidents/analyses").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].simTime").isNumber())
                .andExpect(jsonPath("$[0].report.trainId").value("GREEN-1"))
                .andExpect(jsonPath("$[0].report.injectedDelaySeconds").value(90.0))
                .andExpect(jsonPath("$[0].report.horizonSeconds").value(300.0))
                .andExpect(jsonPath("$[0].report.bestStrategy").isString())
                .andExpect(jsonPath("$[0].report.outcomes.length()").value(HeadwayStrategy.all().size()));
    }

    @Test
    void whatIfForAStationClosureComparesStrategiesWithoutClosingIt() throws Exception {
        mvc.perform(post("/api/analysis/station-closure-impact").param("horizonSeconds", "300")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stationId": "G2", "seconds": 300}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incident.kind").value("STATION_CLOSURE"))
                .andExpect(jsonPath("$.incident.stationId").value("G2"))
                .andExpect(jsonPath("$.incident.trainId").doesNotExist())
                .andExpect(jsonPath("$.horizonSeconds").value(300.0))
                .andExpect(jsonPath("$.outcomes.length()").value(HeadwayStrategy.all().size()))
                .andExpect(jsonPath("$.outcomes[0].totalExtraTravelMinutes").isNumber())
                .andExpect(jsonPath("$.bestStrategy").isString());
        mvc.perform(get("/api/state"))
                .andExpect(jsonPath("$.stations[?(@.id == 'G2')].closed", hasItem(false)));
    }

    @Test
    void whatIfForADoorFaultComparesStrategiesWithoutHoldingTheTrain() throws Exception {
        mvc.perform(post("/api/analysis/door-fault-impact").param("horizonSeconds", "300")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trainId": "BLUE-1", "seconds": 120}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incident.kind").value("DOOR_FAULT"))
                .andExpect(jsonPath("$.incident.trainId").value("BLUE-1"))
                .andExpect(jsonPath("$.incident.stationId").doesNotExist())
                .andExpect(jsonPath("$.outcomes.length()").value(HeadwayStrategy.all().size()))
                .andExpect(jsonPath("$.bestStrategy").isString());
        simulation.tick();
        mvc.perform(get("/api/state"))
                .andExpect(jsonPath("$.activeIncidents[?(@.subjectId == 'BLUE-1')]").isEmpty());
    }

    @Test
    void whatIfForATrackSectionClosureDefaultsToWaiting() throws Exception {
        mvc.perform(post("/api/analysis/section-closure-impact").param("horizonSeconds", "300")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lineId": "RED", "fromStationId": "R3", "toStationId": "R4", "seconds": 300}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incident.kind").value("SECTION_CLOSURE"))
                .andExpect(jsonPath("$.incident.response").value("WAIT"))
                .andExpect(jsonPath("$.outcomes.length()").value(HeadwayStrategy.all().size()));
        mvc.perform(get("/api/state"))
                .andExpect(jsonPath("$.closedSections[*].fromStationId", not(hasItem("R3"))));
    }

    @Test
    void whatIfRejectsSectionsThatAreNotAdjacent() throws Exception {
        mvc.perform(post("/api/analysis/section-closure-impact").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lineId": "RED", "fromStationId": "R1", "toStationId": "R5", "seconds": 300}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("not adjacent")));
    }
}
