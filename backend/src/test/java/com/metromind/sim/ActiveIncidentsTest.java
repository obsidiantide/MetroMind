package com.metromind.sim;

import com.metromind.sim.SimulationSnapshot.ActiveIncidentView;
import com.metromind.sim.SimulationSnapshot.IncidentKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ActiveIncidentsTest {

    private static final double START = 8 * 3600;

    @Test
    void noIncidentsInAQuietNetwork() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.run(120, 1);
        assertThat(engine.snapshot().activeIncidents()).isEmpty();
    }

    @Test
    void listsEveryKindOfIncidentSoonestToClearFirst() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.run(60, 1);
        engine.closeStation("E", 900);
        engine.injectBreakdown("L1-2", 600);
        engine.closeSection("L1", "B", "C", 1200, ClosureResponse.WAIT);
        engine.injectDelay("L1-1", 120);

        assertThat(engine.snapshot().activeIncidents())
                .extracting(ActiveIncidentView::kind, ActiveIncidentView::subjectId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(IncidentKind.TRAIN_DELAY, "L1-1"),
                        org.assertj.core.groups.Tuple.tuple(IncidentKind.BREAKDOWN, "L1-2"),
                        org.assertj.core.groups.Tuple.tuple(IncidentKind.STATION_CLOSURE, "E"),
                        org.assertj.core.groups.Tuple.tuple(IncidentKind.SECTION_CLOSURE, "L1"));
    }

    @Test
    void clearingTimeCountsDownAndTheIncidentDisappearsWhenItClears() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.closeSection("L1", "C", "D", 300, ClosureResponse.TURN_BACK);
        engine.run(100, 1);

        ActiveIncidentView closure = engine.snapshot().activeIncidents().get(0);
        assertThat(closure.startedAt()).isEqualTo(START);
        assertThat(closure.clearsAt()).isCloseTo(START + 300, within(1e-6));
        assertThat(closure.remainingSeconds()).isCloseTo(200, within(1e-6));
        assertThat(closure.description()).isEqualTo("Track C–D on line L1 closed");

        engine.run(201, 1);
        assertThat(engine.snapshot().activeIncidents()).isEmpty();
    }

    @Test
    void aSecondDelayExtendsTheStoppageWithoutMovingItsStart() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.injectDelay("L1-3", 200);
        engine.run(50, 1);
        engine.injectDelay("L1-3", 100);

        ActiveIncidentView delay = engine.snapshot().activeIncidents().get(0);
        assertThat(delay.startedAt()).isEqualTo(START);
        assertThat(delay.clearsAt()).isCloseTo(START + 300, within(1e-6));
    }

    @Test
    void aBreakdownOnADelayedTrainReportsAsABreakdown() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.injectDelay("L1-1", 60);
        engine.injectBreakdown("L1-1", 300);

        assertThat(engine.snapshot().activeIncidents()).singleElement()
                .satisfies(i -> {
                    assertThat(i.kind()).isEqualTo(IncidentKind.BREAKDOWN);
                    assertThat(i.remainingSeconds()).isEqualTo(360);
                });
    }

    @Test
    void stationClosureKeepsItsStartWhenExtended() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.closeStation("C", 300);
        engine.run(100, 1);
        engine.closeStation("C", 600);

        ActiveIncidentView closure = engine.snapshot().activeIncidents().get(0);
        assertThat(closure.startedAt()).isEqualTo(START);
        assertThat(closure.clearsAt()).isCloseTo(START + 700, within(1e-6));
    }

    @Test
    void forkedEngineKeepsIncidentStartTimes() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.injectBreakdown("L1-2", 400);
        engine.closeStation("B", 400);
        engine.closeSection("L1", "D", "E", 400);
        engine.run(30, 1);

        assertThat(engine.copy().snapshot().activeIncidents()).isEqualTo(engine.snapshot().activeIncidents());
    }
}
