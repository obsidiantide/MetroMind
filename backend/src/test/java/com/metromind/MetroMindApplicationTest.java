package com.metromind;

import com.metromind.network.MetroNetwork;
import com.metromind.sim.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("standalone")
class MetroMindApplicationTest {

    @Autowired
    MetroNetwork network;

    @Autowired
    SimulationService simulation;

    @Test
    void loadsSeedNetworkAndRunsSimulation() {
        assertThat(network.lines()).hasSize(3);
        double t0 = simulation.snapshot().simTime();
        simulation.tick();
        assertThat(simulation.snapshot().simTime()).isGreaterThan(t0);
    }
}
