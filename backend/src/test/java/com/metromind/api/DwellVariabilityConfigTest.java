package com.metromind.api;

import com.metromind.sim.DwellVariability;
import com.metromind.sim.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"metromind.sim.dwell-variability=0.12", "metromind.sim.run-time-seed=17"})
@ActiveProfiles("standalone")
class DwellVariabilityConfigTest {

    @Autowired
    SimulationService simulation;

    @Test
    void theLiveEngineDrawsDwellsWithTheConfiguredSpreadAndSeed() {
        assertThat(simulation.dwellVariability()).isEqualTo(new DwellVariability(0.12, 17));
    }
}
