package com.metromind.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.SimulationSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** The bundled Baku network: a real city laid out from GeoJSON, with run times derived from its track. */
class BakuNetworkTest {

    private static final double MORNING = 8 * 3600;

    private static MetroNetwork baku() throws Exception {
        return NetworkLoader.load(new ObjectMapper(), new ClassPathResource("network/baku.geojson"));
    }

    @Test
    void loadsThreeLinesSharingTwoInterchanges() throws Exception {
        MetroNetwork n = baku();
        assertThat(n.city()).isEqualTo("Baku");
        assertThat(n.lines()).extracting(Line::id).containsExactly("M1", "M2", "M3");
        assertThat(n.line("M1").stationIds()).contains("28may").startsWith("icherisheher").endsWith("aslanov");
        assertThat(n.line("M2").stationIds()).contains("28may", "ajami");
        assertThat(n.line("M3").stationIds()).contains("ajami");
        assertThat(n.lines()).allMatch(Line::hasPath);
    }

    @Test
    void someRedLineTrainsBranchOffToBakmilAtNarimanov() throws Exception {
        Line red = baku().line("M1");
        assertThat(red.branches()).singleElement().satisfies(b -> {
            assertThat(b.junctionStationId()).isEqualTo("narimanov");
            assertThat(b.stationIds()).containsExactly("bakmil");
            assertThat(b.hasPath()).isTrue();
        });
        assertThat(red.patterns()).extracting(p -> p.id() + ":" + p.trainCount())
                .containsExactly("M1:8", "M1/bakmil:2");
        assertThat(red.patterns().get(1).stationIds()).startsWith("icherisheher").endsWith("narimanov", "bakmil");

        SimulationEngine engine = new SimulationEngine(baku(), MORNING, HeadwayStrategy.BALANCED);
        engine.run(3600, 1);
        assertThat(engine.stationDelivered("bakmil")).isPositive();
        assertThat(engine.route("bakmil", "aslanov")).containsExactly("M1", "M1");
    }

    @Test
    void derivedRunTimesAreThoseOfARealMetro() throws Exception {
        for (Line line : baku().lines()) {
            // Baku's inter-station spacing is roughly 1–3.5 km: two to six and a half minutes at metro pace.
            assertThat(line.runSeconds()).as(line.id()).allSatisfy(s -> assertThat(s).isBetween(90, 400));
        }
        // Gənclik–Nəriman Nərimanov is one of the shortest hops, Ulduz–Koroğlu one of the longest.
        Line red = baku().line("M1");
        int ganjlik = red.stationIds().indexOf("ganjlik");
        int ulduz = red.stationIds().indexOf("ulduz");
        assertThat(red.runSeconds().get(ganjlik)).isLessThan(red.runSeconds().get(ulduz));
    }

    @Test
    void simulatesDeterministicallyWithTransfersAtInterchanges() throws Exception {
        SimulationEngine a = new SimulationEngine(baku(), MORNING, HeadwayStrategy.BALANCED);
        SimulationEngine b = new SimulationEngine(baku(), MORNING, HeadwayStrategy.BALANCED);
        a.run(3600, 1);
        b.run(3600, 1);

        SimulationSnapshot.Metrics m = a.snapshot().metrics();
        assertThat(m.passengersDelivered()).isPositive();
        assertThat(a.stationTransfers("28may") + a.stationTransfers("ajami")).isPositive();
        assertThat(a.snapshot().trains()).allSatisfy(t -> {
            assertThat(t.lat()).isBetween(40.3, 40.5);
            assertThat(t.lon()).isBetween(49.7, 50.0);
        });
        assertThat(b.snapshot().metrics()).isEqualTo(m);
    }
}
