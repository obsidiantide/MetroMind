package com.metromind.api;

import com.metromind.sim.SimulationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class MetricsApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SimulationService simulation;

    @Autowired
    MeterRegistry meters;

    @Test
    void timesSimulationTicks() throws Exception {
        long before = meters.get("metromind.sim.tick").timer().count();
        simulation.tick();
        simulation.tick();
        assertThat(meters.get("metromind.sim.tick").timer().count()).isEqualTo(before + 2);
        mvc.perform(get("/actuator/metrics/metromind.sim.tick"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value").isNotEmpty());
    }

    @Test
    void timesAnalysesByKind() throws Exception {
        mvc.perform(post("/api/analysis/delay-impact").param("horizonSeconds", "120")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"trainId\":\"RED-1\",\"seconds\":60}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/analysis/station-closure-impact").param("horizonSeconds", "120")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stationId\":\"R3\",\"seconds\":60}"))
                .andExpect(status().isOk());
        Timer delay = meters.get("metromind.analysis.duration").tag("kind", "delay").timer();
        Timer closure = meters.get("metromind.analysis.duration").tag("kind", "station_closure").timer();
        assertThat(delay.count()).isPositive();
        assertThat(closure.count()).isPositive();
        assertThat(delay.totalTime(TimeUnit.NANOSECONDS)).isPositive();
    }

    @Test
    void gaugesReadTheLatestSnapshot() throws Exception {
        simulation.tick();
        assertThat(meters.get("metromind.sim.time").gauge().value()).isEqualTo(simulation.snapshot().simTime());
        assertThat(meters.get("metromind.trains.in.service").gauge().value())
                .isEqualTo(simulation.snapshot().metrics().trainsInService());
        mvc.perform(get("/actuator/metrics/metromind.passengers.waiting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUnit").value("passengers"));
    }

    @Test
    void countsInjectedIncidentsByKind() throws Exception {
        double before = meters.counter("metromind.incidents.injected", "kind", "breakdown").count();
        mvc.perform(post("/api/incidents/breakdown").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trainId\":\"BLUE-1\",\"seconds\":60}"))
                .andExpect(status().isAccepted());
        assertThat(meters.counter("metromind.incidents.injected", "kind", "breakdown").count())
                .isEqualTo(before + 1);
        mvc.perform(get("/actuator/metrics/metromind.incidents.injected").param("tag", "kind:breakdown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value", greaterThan(0.0)));
    }
}
