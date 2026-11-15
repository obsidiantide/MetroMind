package com.metromind.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class StationSearchApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void findsTheNearestStations() throws Exception {
        // A few metres from Central (R3, 40.375 / 49.82); University (R4) is the next one along the red line.
        mvc.perform(get("/api/stations/nearest").param("lat", "40.3751").param("lon", "49.8201").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].station.id").value("R3"))
                .andExpect(jsonPath("$[0].station.name").value("Central"))
                .andExpect(jsonPath("$[0].distanceMeters", closeTo(14.0, 1.0)));
    }

    @Test
    void findsStationsWithinARadius() throws Exception {
        // Old Market (R2) and University (R4) are ~1.7 km from Central.
        mvc.perform(get("/api/stations/within").param("lat", "40.375").param("lon", "49.82")
                        .param("radiusMeters", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].station.id").value("R3"))
                .andExpect(jsonPath("$[0].distanceMeters").value(0.0));
        mvc.perform(get("/api/stations/within").param("lat", "40.375").param("lon", "49.82")
                        .param("radiusMeters", "2000"))
                .andExpect(jsonPath("$[*].station.id", hasItems("R2", "R3", "R4")));
    }

    @Test
    void invalidPointOrRadiusIsABadRequest() throws Exception {
        mvc.perform(get("/api/stations/nearest").param("lat", "95").param("lon", "49.82"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("not a valid WGS84")));
        mvc.perform(get("/api/stations/within").param("lat", "40").param("lon", "49").param("radiusMeters", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stations/nearest").param("lat", "40"))
                .andExpect(status().isBadRequest());
    }
}
