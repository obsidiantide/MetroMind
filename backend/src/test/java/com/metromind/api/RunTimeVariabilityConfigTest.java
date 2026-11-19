package com.metromind.api;

import com.metromind.sim.RunTimeVariability;
import com.metromind.sim.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"metromind.sim.run-time-variability=0.06", "metromind.sim.run-time-seed=17"})
@ActiveProfiles("standalone")
class RunTimeVariabilityConfigTest {

    @Autowired
    SimulationService simulation;

    @Test
    void theLiveEngineDrawsRunTimesWithTheConfiguredSpreadAndSeed() {
        assertThat(simulation.runTimeVariability()).isEqualTo(new RunTimeVariability(0.06, 17));
    }
}
