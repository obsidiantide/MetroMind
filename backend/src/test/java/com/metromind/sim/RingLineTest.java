package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RingLineTest {

    private static final double START = 8 * 3600;

    private static SimulationEngine engine(HeadwayStrategy strategy) {
        return new SimulationEngine(TestNetworks.ring(), START, strategy);
    }

    @Test
    void trainsStartEvenlySpacedRoundTheRing() {
        SimulationEngine engine = engine(HeadwayStrategy.NO_CONTROL);
        // Ring of 6 x 145 s, 3 trains: one every 290 s.
        for (TrainState t : engine.trains()) {
            assertThat(engine.forwardHeadway(t)).isCloseTo(290, within(1e-6));
        }
        assertThat(engine.headwayCv()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void trainsRunRoundTheRingWithoutReversing() {
        SimulationEngine engine = engine(HeadwayStrategy.NO_CONTROL);
        Map<String, String> lastFrom = new HashMap<>();
        Map<String, Integer> wraps = new HashMap<>();
        for (int i = 0; i < 2 * 870; i++) {
            engine.step(1);
            for (var t : engine.snapshot().trains()) {
                String prev = lastFrom.put(t.id(), t.fromStationId());
                if (prev != null && !prev.equals(t.fromStationId())) {
                    // Every move is one station on, in ring order; F is followed by A.
                    assertThat(t.fromStationId().charAt(0) - 'A')
                            .isEqualTo((prev.charAt(0) - 'A' + 1) % 6);
                    if (prev.equals("F")) {
                        wraps.merge(t.id(), 1, Integer::sum);
                    }
                }
                if (t.phase() == TrainPhase.RUNNING && t.fromStationId().equals("F")) {
                    assertThat(t.toStationId()).isEqualTo("A");
                }
            }
        }
        assertThat(wraps).hasSize(3).allSatisfy((id, n) -> assertThat(n).isGreaterThanOrEqualTo(1));
        assertThat(engine.snapshot().metrics().passengersDelivered()).isPositive();
    }

    @Test
    void stationsHaveOnePlatformQueueSignedTowardsTheNextStation() {
        SimulationEngine engine = engine(HeadwayStrategy.NO_CONTROL);
        engine.run(300, 1);
        for (var st : engine.snapshot().stations()) {
            assertThat(st.queues()).singleElement().satisfies(q -> assertThat(q.towardsStationId())
                    .isEqualTo(String.valueOf((char) ('A' + (st.id().charAt(0) - 'A' + 1) % 6))));
        }
    }

    @Test
    void passengersRideTheShortWayRoundThroughTheClosingSection() {
        SimulationEngine engine = engine(HeadwayStrategy.NO_CONTROL);
        RoutePlanner routes = new RoutePlanner(TestNetworks.ring(), SimulationEngine.NOMINAL_DWELL);
        double fToA = routes.travelSeconds(routes.index("F"), routes.index("A"));
        double aToF = routes.travelSeconds(routes.index("A"), routes.index("F"));
        // Half the 290 s headway to wait, then one 145 s hop versus five.
        assertThat(fToA).isCloseTo(145 + 145, within(1e-6));
        assertThat(aToF).isCloseTo(145 + 5 * 145, within(1e-6));
        assertThat(engine.departures("F", 900)).allSatisfy(d -> assertThat(d.towardsStationId()).isEqualTo("A"));
    }

    @Test
    void closingTheSectionThatClosesTheRingHoldsTrainsBeforeIt() {
        SimulationEngine engine = engine(HeadwayStrategy.NO_CONTROL);
        engine.closeSection("K", "A", "F", 1200, ClosureResponse.TURN_BACK);
        assertThat(engine.snapshot().closedSections()).singleElement().satisfies(c -> {
            assertThat(c.fromStationId()).isEqualTo("F");
            assertThat(c.toStationId()).isEqualTo("A");
        });
        engine.run(900, 1);
        // Nothing can reverse on a ring, so the first train to reach F waits there for the section to reopen.
        assertThat(engine.trains()).anySatisfy(t -> {
            assertThat(t.stop()).isEqualTo(5);
            assertThat(t.phase()).isEqualTo(TrainPhase.WAITING_SIGNAL);
        });
        assertThat(engine.trains()).noneSatisfy(t -> assertThat(t.stop()).isZero());
    }

    @Test
    void lateTrainsAreNeverShortTurnedOnARing() {
        SimulationEngine engine = engine(HeadwayStrategy.SHORT_TURN);
        engine.injectDelay("K-1", 400);
        engine.run(1800, 1);
        assertThat(engine.snapshot().recentEvents())
                .noneMatch(e -> e.type() == SimEvent.Type.TRAIN_SHORT_TURNED);
    }

    @Test
    void earlyTrainsWaitForTheTimetableOnlyAtTheRingsFirstStation() {
        // Off-peak, dwells are shorter than nominal, so trains run early. Without control the timetable is the
        // only reason to hold, and a ring keeps it at its first station.
        SimulationEngine engine = new SimulationEngine(TestNetworks.ring(), 11 * 3600, HeadwayStrategy.NO_CONTROL);
        java.util.Set<Integer> heldAt = new java.util.TreeSet<>();
        for (int i = 0; i < 2 * 870; i++) {
            engine.step(1);
            engine.trains().stream().filter(t -> t.phase() == TrainPhase.HELD).forEach(t -> heldAt.add(t.stop()));
        }
        assertThat(heldAt).containsExactly(0);
    }

    @Test
    void closingAStationSendsItsPassengersToBothRingNeighbours() {
        SimulationEngine engine = engine(HeadwayStrategy.NO_CONTROL);
        engine.run(600, 1);
        engine.closeStation("A", 600);
        var held = engine.snapshot().stations().stream()
                .filter(s -> s.heldAtEntrance() > 0).map(SimulationSnapshot.StationView::id).toList();
        assertThat(held).containsExactlyInAnyOrder("B", "F");
    }

    @Test
    void ringRunsAreDeterministic() {
        SimulationEngine a = engine(HeadwayStrategy.BALANCED);
        SimulationEngine b = engine(HeadwayStrategy.BALANCED);
        a.injectDelay("K-2", 180);
        b.injectDelay("K-2", 180);
        a.run(1800, 1);
        b.run(1800, 1);
        assertThat(a.snapshot().metrics()).isEqualTo(b.snapshot().metrics());
        assertThat(a.trains().stream().map(TrainState::stop).toList())
                .isEqualTo(b.trains().stream().map(TrainState::stop).toList());
        assertThat(List.of(a.headwayCv())).isEqualTo(List.of(b.headwayCv()));
    }
}
