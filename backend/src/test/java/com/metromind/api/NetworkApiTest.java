package com.metromind.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "metromind.network=classpath:network/baku.geojson")
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class NetworkApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listsTheServicePatternsOfABranchedLine() throws Exception {
        mvc.perform(get("/api/network"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].id").value("M1"))
                .andExpect(jsonPath("$.lines[0].patterns[*].id", contains("M1", "M1/bakmil")))
                .andExpect(jsonPath("$.lines[0].patterns[*].trainCount", contains(8, 2)))
                .andExpect(jsonPath("$.lines[0].patterns[1].stationIds[0]").value("icherisheher"))
                .andExpect(jsonPath("$.lines[0].patterns[1].stationIds[-1]").value("bakmil"))
                .andExpect(jsonPath("$.lines[0].patterns[1].runSeconds").isArray());
    }

    @Test
    void anUnbranchedLineHasOnePatternWithAllItsTrains() throws Exception {
        mvc.perform(get("/api/network"))
                .andExpect(jsonPath("$.lines[1].patterns", hasSize(1)))
                .andExpect(jsonPath("$.lines[1].patterns[0].id").value("M2"));
    }

    @Test
    void namesThePatternOfEachTrainInTheSnapshot() throws Exception {
        mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trains[?(@.patternId == 'M1/bakmil')]", hasSize(2)))
                .andExpect(jsonPath("$.trains[?(@.lineId == 'M2')].patternId", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("M2"))));
    }

    @Test
    void reportsTrunkRegularityOnlyForTheBranchedLine() throws Exception {
        mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.trunkHeadwayCv.M1").isNumber())
                .andExpect(jsonPath("$.metrics.trunkHeadwayCv.M2").doesNotExist());
    }
}
