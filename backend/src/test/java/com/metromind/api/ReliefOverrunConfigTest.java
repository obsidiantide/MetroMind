package com.metromind.api;

import com.metromind.sim.ReliefOverrun;
import com.metromind.sim.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"metromind.sim.relief-overrun-share=0.15", "metromind.sim.relief-overrun-seconds=90",
        "metromind.sim.run-time-seed=17"})
@ActiveProfiles("standalone")
class ReliefOverrunConfigTest {

    @Autowired
    SimulationService simulation;

    @Test
    void theLiveEngineDrawsLateReliefsWithTheConfiguredShareMeanAndSeed() {
        assertThat(simulation.reliefOverrun()).isEqualTo(new ReliefOverrun(0.15, 90, 17));
    }
}
