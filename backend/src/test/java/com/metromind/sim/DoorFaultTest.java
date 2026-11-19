package com.metromind.sim;

import com.metromind.sim.SimulationSnapshot.ActiveIncidentView;
import com.metromind.sim.SimulationSnapshot.IncidentKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DoorFaultTest {

    private static final double START = 8 * 3600;

    /** The single test train, run until it is in the given phase. */
    private static SimulationEngine trainIn(TrainPhase phase) {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        engine.setRecordArrivals(true);
        while (engine.train("L1-1").phase() != phase) {
            engine.step(1);
        }
        return engine;
    }

    private static double departureAfter(SimulationEngine engine) {
        TrainState t = engine.train("L1-1");
        int stop = t.stop();
        while (t.stop() == stop && t.phase() != TrainPhase.RUNNING) {
            engine.step(1);
            t = engine.train("L1-1");
        }
        return engine.simTime();
    }

    @Test
    void aTrainAtAPlatformStaysThereForTheExtraDwell() {
        SimulationEngine faulty = trainIn(TrainPhase.DWELLING);
        SimulationEngine normal = faulty.copy();
        faulty.injectDoorFault("L1-1", 90);

        assertThat(departureAfter(faulty) - departureAfter(normal)).isCloseTo(90, within(1.0));
        assertThat(faulty.train("L1-1").delaySeconds()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aRunningTrainIsNotStoppedOnTheTrackButHeldAtItsNextStop() {
        SimulationEngine faulty = trainIn(TrainPhase.RUNNING);
        SimulationEngine normal = faulty.copy();
        faulty.injectDoorFault("L1-1", 60);

        // It keeps running and reaches the next platform as it would have...
        int next = faulty.train("L1-1").stop() + 1;
        while (faulty.train("L1-1").stop() != next) {
            faulty.step(1);
            normal.step(1);
        }
        assertThat(faulty.train("L1-1").arrivals()).isEqualTo(normal.train("L1-1").arrivals());
        assertThat(faulty.snapshot().activeIncidents()).extracting(ActiveIncidentView::kind)
                .containsExactly(IncidentKind.DOOR_FAULT);

        // ...and leaves it a minute late.
        assertThat(departureAfter(faulty) - departureAfter(normal)).isCloseTo(60, within(1.0));
    }

    @Test
    void theFaultShowsAsAnActiveIncidentUntilTheExtraDwellIsOver() {
        SimulationEngine engine = trainIn(TrainPhase.DWELLING);
        engine.injectDoorFault("L1-1", 120);

        ActiveIncidentView fault = engine.snapshot().activeIncidents().get(0);
        assertThat(fault.kind()).isEqualTo(IncidentKind.DOOR_FAULT);
        assertThat(fault.subjectId()).isEqualTo("L1-1");
        assertThat(fault.description()).isEqualTo("Train L1-1 held by a door fault");
        assertThat(fault.startedAt()).isEqualTo(engine.simTime());
        assertThat(fault.remainingSeconds()).isGreaterThanOrEqualTo(120);

        engine.run(fault.remainingSeconds() + 1, 1);
        assertThat(engine.snapshot().activeIncidents()).isEmpty();
        assertThat(engine.snapshot().recentEvents()).anyMatch(e -> e.type() == SimEvent.Type.INCIDENT
                && e.message().startsWith("Train L1-1 has a door fault, held 120 s at"));
    }

    @Test
    void aTrainWaitingForASignalStillWaitsOutItsDoors() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.closeSection("L1", "C", "D", 600, ClosureResponse.WAIT);
        while (engine.trains().stream().noneMatch(t -> t.phase() == TrainPhase.WAITING_SIGNAL)) {
            engine.step(1);
        }
        TrainState waiting = engine.trains().stream().filter(t -> t.phase() == TrainPhase.WAITING_SIGNAL)
                .findFirst().orElseThrow();
        engine.injectDoorFault(waiting.id(), 30);
        assertThat(engine.train(waiting.id()).phase()).isEqualTo(TrainPhase.HELD);
    }

    @Test
    void doorFaultsAreDeterministicAndCopied() {
        SimulationEngine a = trainIn(TrainPhase.RUNNING);
        a.injectDoorFault("L1-1", 45);
        SimulationEngine b = a.copy();
        a.run(1800, 1);
        b.run(1800, 1);
        assertThat(b.train("L1-1").arrivals()).isEqualTo(a.train("L1-1").arrivals());
    }
}
