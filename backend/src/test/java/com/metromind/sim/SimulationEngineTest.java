package com.metromind.sim;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SimulationEngineTest {

    private static final double START = 8 * 3600;

    @Test
    void loopMapsStopsToStationsInBothDirections() {
        Line line = TestNetworks.singleLine().line("L1");
        assertThat(line.loopLength()).isEqualTo(10);
        assertThat(line.stationAtStop(0)).isEqualTo("A");
        assertThat(line.stationAtStop(5)).isEqualTo("F");
        assertThat(line.stationAtStop(6)).isEqualTo("E");
        assertThat(line.stationAtStop(9)).isEqualTo("B");
        assertThat(line.directionAtStop(4)).isEqualTo(1);
        assertThat(line.directionAtStop(5)).isEqualTo(-1);
    }

    @Test
    void trainsStartOnDistinctStopsAndMove() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        Set<Integer> stops = new HashSet<>();
        engine.trains().forEach(t -> stops.add(t.stop()));
        assertThat(stops).hasSize(4);

        List<Integer> before = engine.trains().stream().map(TrainState::stop).toList();
        engine.run(600, 1);
        List<Integer> after = engine.trains().stream().map(TrainState::stop).toList();
        assertThat(after).isNotEqualTo(before);
        assertThat(engine.snapshot().metrics().passengersServed()).isPositive();
    }

    @Test
    void trainsStartEvenlySpacedAlongTheLoop() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        // Loop of 10 x 145 s, 4 trains: one every 362.5 s, which is not a whole number of stops.
        for (TrainState t : engine.trains()) {
            assertThat(engine.forwardHeadway(t)).isCloseTo(362.5, within(1e-6));
        }
        assertThat(engine.headwayCv()).isCloseTo(0.0, within(1e-9));
        assertThat(engine.train("L1-2").phase()).isEqualTo(TrainPhase.RUNNING);
        assertThat(engine.train("L1-2").delaySeconds()).isZero();
    }

    @Test
    void unevenRunTimesStillStartWithEvenHeadways() {
        Line line = new Line("R", "Red", "#f00", List.of("A", "B", "C", "D", "E", "F"),
                List.of(191, 193, 193, 193, 193), 6, 900);
        var network = new com.metromind.network.MetroNetwork("Test", TestNetworks.singleLine().stations(),
                List.of(line));
        SimulationEngine engine = new SimulationEngine(network, START, HeadwayStrategy.NO_CONTROL);
        assertThat(engine.headwayCv()).isLessThan(0.05);
    }

    @Test
    void trainsFollowTheTrackAlignmentWhenTheNetworkHasOne() {
        // Two stations on an east-west axis; the track detours ~1.1 km north between them.
        var stations = List.of(new com.metromind.network.Station("A", "Alpha", 40.00, 49.80, 5),
                new com.metromind.network.Station("B", "Bravo", 40.00, 49.82, 5));
        Line curved = new Line("L", "Loop", "#f00", List.of("A", "B"), List.of(300), 1, 900, 0,
                List.of(List.of(49.80, 40.00), List.of(49.81, 40.01), List.of(49.82, 40.00)));
        SimulationEngine engine = new SimulationEngine(
                new com.metromind.network.MetroNetwork("Test", stations, List.of(curved)), START,
                HeadwayStrategy.NO_CONTROL);

        boolean sawMidSegment = false;
        for (int i = 0; i < 700; i++) {
            engine.step(1);
            var train = engine.snapshot().trains().get(0);
            if (train.phase() == TrainPhase.RUNNING) {
                // On the chord the latitude would stay 40.00; on the track it rises towards the apex.
                assertThat(train.lat()).isCloseTo(40.00 + 0.01 * (1 - Math.abs(2 * train.progress() - 1)),
                        within(1e-4));
                sawMidSegment |= train.progress() > 0.3 && train.progress() < 0.7;
            }
        }
        assertThat(sawMidSegment).isTrue();
    }

    @Test
    void ridingTimeIntegratesPassengersOnBoard() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.run(900, 1);
        double onboard = engine.trains().stream().mapToDouble(TrainState::passengers).sum();
        assertThat(onboard).isPositive();
        double before = engine.passengerRideSeconds();
        SimulationEngine copy = engine.copy();
        assertThat(copy.passengerRideSeconds()).isEqualTo(before);

        engine.step(1);
        assertThat(engine.passengerRideSeconds() - before).isCloseTo(onboard, within(1e-6));
    }

    @Test
    void earlyTrainsWaitAtTerminalsForTheirTimetabledDeparture() {
        // Night-time demand: dwells are at the 15 s minimum, so trains gain 10 s per stop on the 25 s timetable.
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), 3 * 3600,
                HeadwayStrategy.NO_CONTROL);
        Line line = engine.network().line("L1");
        boolean heldAtTerminal = false;
        boolean leftIntermediateEarly = false;
        for (int i = 0; i < 4 * 3600; i++) {
            List<TrainState> before = engine.trains().stream().map(TrainState::copy).toList();
            engine.step(1);
            for (TrainState was : before) {
                TrainState now = engine.train(was.id());
                heldAtTerminal |= now.phase() == TrainPhase.HELD && (now.stop() == 0 || now.stop() == 5);
                if (was.phase() != TrainPhase.RUNNING && now.phase() == TrainPhase.RUNNING) {
                    double planned = engine.plannedDeparture(now);
                    if (now.stop() == 0 || now.stop() == line.stationIds().size() - 1) {
                        assertThat(engine.simTime()).isGreaterThanOrEqualTo(planned - 1e-9);
                    } else if (engine.simTime() < planned - 5) {
                        leftIntermediateEarly = true;
                    }
                }
            }
        }
        assertThat(heldAtTerminal).isTrue();
        assertThat(leftIntermediateEarly).isTrue();
    }

    @Test
    void earlyTrainsWaitOnlyAtTheLinesConfiguredTimingPoints() {
        Line base = TestNetworks.singleLine().line("L1");
        Line timedAtC = new Line(base.id(), base.name(), base.color(), base.stationIds(), base.runSeconds(),
                base.trainCount(), base.trainCapacity(), 0, List.of(), false, List.of("C"));
        SimulationEngine engine = new SimulationEngine(new MetroNetwork("Test", TestNetworks.singleLine().stations(),
                List.of(timedAtC)), 3 * 3600, HeadwayStrategy.NO_CONTROL);
        java.util.Set<String> heldAt = new java.util.TreeSet<>();
        for (int i = 0; i < 2 * 3600; i++) {
            List<TrainState> before = engine.trains().stream().map(TrainState::copy).toList();
            engine.step(1);
            for (TrainState was : before) {
                TrainState now = engine.train(was.id());
                if (now.phase() == TrainPhase.HELD) {
                    heldAt.add(timedAtC.stationAtStop(now.stop()));
                }
                if (was.phase() != TrainPhase.RUNNING && now.phase() == TrainPhase.RUNNING
                        && timedAtC.stationAtStop(now.stop()).equals("C")) {
                    assertThat(engine.simTime()).isGreaterThanOrEqualTo(engine.plannedDeparture(now) - 1e-9);
                }
            }
        }
        // Held at C in both directions, and no longer at the terminals A and F.
        assertThat(heldAt).containsExactly("C");
    }

    @Test
    void neverTwoTrainsInTheSameBlock() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.injectBreakdown("L1-1", 900);
        for (int i = 0; i < 3600; i++) {
            engine.step(1);
            List<TrainState> running = engine.trains().stream().filter(t -> t.phase() == TrainPhase.RUNNING).toList();
            assertThat(running.stream().map(TrainState::stop).distinct().count()).isEqualTo(running.size());
        }
    }

    @Test
    void injectedDelayIsDetected() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.injectDelay("L1-2", 240);
        engine.run(900, 1);
        assertThat(engine.snapshot().recentEvents())
                .anyMatch(e -> e.type() == SimEvent.Type.DELAY_DETECTED && e.subjectId().equals("L1-2"));
    }

    @Test
    void copiesAreIndependentAndDeterministic() {
        SimulationEngine a = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.BALANCED);
        a.run(300, 1);
        SimulationEngine b = a.copy();
        a.run(600, 1);
        b.run(600, 1);
        assertThat(b.snapshot().trains()).isEqualTo(a.snapshot().trains());

        SimulationEngine c = a.copy();
        c.injectDelay("L1-1", 300);
        c.run(600, 1);
        a.run(600, 1);
        assertThat(c.snapshot().trains()).isNotEqualTo(a.snapshot().trains());
    }

    @Test
    void stationLoadPredictionFlagsOverload() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.injectBreakdown("L1-1", 3600);
        engine.run(2400, 1);
        double predicted = engine.predictWaiting("C", 900);
        assertThat(predicted).isGreaterThan(engine.stationWaiting("C") * 0.5);
        assertThat(SimulationEngine.riskLevel(500)).isEqualTo("HIGH");
        assertThat(SimulationEngine.riskLevel(250)).isEqualTo("MEDIUM");
        assertThat(SimulationEngine.riskLevel(10)).isEqualTo("LOW");
    }

    @Test
    void demandHasMorningAndEveningPeaks() {
        assertThat(DemandProfile.multiplier(8.25 * 3600)).isGreaterThan(DemandProfile.multiplier(12 * 3600));
        assertThat(DemandProfile.multiplier(18 * 3600)).isGreaterThan(DemandProfile.multiplier(14 * 3600));
        assertThat(DemandProfile.multiplier(3 * 3600)).isLessThan(0.5);
    }

    @Test
    void demandSplitsByDestinationsAheadInEachDirection() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.step(1);
        double outbound = engine.stationWaiting("B", "L1", 1);
        double inbound = engine.stationWaiting("B", "L1", -1);
        assertThat(outbound).isPositive();
        // Gravity model: from B, destinations C..F (12 + 8 + 6 + 4) versus A (5).
        assertThat(outbound / inbound).isCloseTo(30.0 / 5.0, within(1e-9));
        assertThat(outbound + inbound).isCloseTo(engine.stationWaiting("B"), within(1e-9));
        assertThat(engine.stationWaiting("B", "L1", 1, "C") / outbound).isCloseTo(12.0 / 30.0, within(1e-9));
    }

    @Test
    void terminalsOnlyQueueTowardsTheOtherEnd() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.step(1);
        assertThat(engine.stationWaiting("A", "L1", -1)).isZero();
        assertThat(engine.stationWaiting("A", "L1", 1)).isEqualTo(engine.stationWaiting("A"));
        assertThat(engine.stationWaiting("F", "L1", 1)).isZero();
        assertThat(engine.stationWaiting("F", "L1", -1)).isEqualTo(engine.stationWaiting("F"));

        SimulationSnapshot.StationView a = engine.snapshot().stations().stream()
                .filter(s -> s.id().equals("A")).findFirst().orElseThrow();
        assertThat(a.queues()).singleElement().satisfies(q -> {
            assertThat(q.lineId()).isEqualTo("L1");
            assertThat(q.towardsStationId()).isEqualTo("F");
        });
    }

    @Test
    void trainBoardsOnlyPassengersTravellingInItsDirection() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        TrainState train = engine.train("L1-1");
        // The only train starts at A heading outbound; wait until it has called at C on the way out.
        while (!(train.stop() == 2 && train.phase() == TrainPhase.DWELLING)) {
            engine.step(1);
        }
        assertThat(engine.stationWaiting("C", "L1", 1)).isCloseTo(0.0, within(1e-9));
        assertThat(engine.stationWaiting("C", "L1", -1)).isPositive();

        // On the way back it serves the inbound queue and leaves the (refilled) outbound one alone.
        while (!(train.stop() == 8 && train.phase() == TrainPhase.DWELLING)) {
            engine.step(1);
        }
        assertThat(engine.stationWaiting("C", "L1", -1)).isCloseTo(0.0, within(1e-9));
        assertThat(engine.stationWaiting("C", "L1", 1)).isPositive();
    }

    @Test
    void predictionDoesNotLetOneDirectionOffsetTheOther() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.NO_CONTROL);
        TrainState train = engine.train("L1-1");
        while (!(train.stop() == 2 && train.phase() == TrainPhase.DWELLING)) {
            engine.step(1);
        }
        // The train is about to leave C outbound and will not return inbound within 60 s, so the inbound
        // queue can only grow while the outbound queue is about to be served.
        double inbound = engine.stationWaiting("C", "L1", -1);
        assertThat(engine.predictWaiting("C", 60)).isGreaterThanOrEqualTo(inbound);
    }
}
