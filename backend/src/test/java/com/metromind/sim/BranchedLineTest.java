package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class BranchedLineTest {

    private static final double START = 8 * 3600;
    private static final Set<String> MAIN_ONLY = Set.of("C", "D");
    private static final Set<String> BRANCH_ONLY = Set.of("X", "Y");

    private static SimulationEngine engine(HeadwayStrategy strategy) {
        return new SimulationEngine(TestNetworks.branched(), START, strategy);
    }

    @Test
    void trainsAreSharedBetweenTheMainRouteAndTheBranch() {
        Map<String, Long> perPattern = engine(HeadwayStrategy.NO_CONTROL).trains().stream()
                .collect(Collectors.groupingBy(TrainState::patternId, Collectors.counting()));
        assertThat(perPattern).containsExactlyInAnyOrderEntriesOf(Map.of("L", 4L, "L/Y", 2L));
    }

    @Test
    void eachTrainOnlyCallsAtTheStationsOfItsOwnPattern() {
        SimulationEngine engine = engine(HeadwayStrategy.BALANCED);
        Map<String, String> patternOf = engine.trains().stream()
                .collect(Collectors.toMap(TrainState::id, TrainState::patternId));
        Set<String> visitedByBranchTrains = new HashSet<>();
        Set<String> visitedByMainTrains = new HashSet<>();
        for (int i = 0; i < 3600; i++) {
            engine.step(1);
            for (var t : engine.snapshot().trains()) {
                Set<String> visited = patternOf.get(t.id()).equals("L") ? visitedByMainTrains : visitedByBranchTrains;
                visited.add(t.fromStationId());
                visited.add(t.toStationId());
            }
        }
        assertThat(visitedByMainTrains).containsAll(MAIN_ONLY).doesNotContainAnyElementsOf(BRANCH_ONLY);
        assertThat(visitedByBranchTrains).containsAll(BRANCH_ONLY).doesNotContainAnyElementsOf(MAIN_ONLY);
    }

    @Test
    void trainsOfBothPatternsNeverShareAPlatformOrATrackSection() {
        SimulationEngine engine = engine(HeadwayStrategy.NO_CONTROL);
        engine.injectDelay("L-1", 240); // bunch the service up so trains queue for the trunk
        for (int i = 0; i < 3600; i++) {
            engine.step(1);
            List<String> blocks = engine.snapshot().trains().stream()
                    .map(t -> t.phase() == TrainPhase.RUNNING
                            ? t.fromStationId() + ">" + t.toStationId()
                            : t.fromStationId() + "@" + platformDirection(engine, t.id()))
                    .toList();
            assertThat(blocks).doesNotHaveDuplicates();
        }
    }

    private static int platformDirection(SimulationEngine engine, String trainId) {
        TrainState t = engine.train(trainId);
        var pattern = engine.network().line(t.lineId()).patterns().stream()
                .filter(p -> p.id().equals(t.patternId())).findFirst().orElseThrow();
        return pattern.directionAtStop(t.stop());
    }

    @Test
    void passengersReachTheBranchAndTheFarEndOfTheMainRoute() {
        SimulationEngine engine = engine(HeadwayStrategy.BALANCED);
        engine.run(3600, 1);
        assertThat(engine.stationDelivered("Y")).isPositive();
        assertThat(engine.stationDelivered("D")).isPositive();
        // Passengers between the branch and the far end of the main route change trains at the junction B.
        assertThat(engine.route("Y", "D")).containsExactly("L", "L");
        assertThat(engine.stationTransfers("B")).isPositive();
        assertThat(engine.stationCarriedPast("B")).isZero();
    }

    @Test
    void branchPassengersWaitForTheirOwnTrainOnTheTrunk() {
        SimulationEngine engine = engine(HeadwayStrategy.NO_CONTROL);
        engine.run(1800, 1);
        String mainTrainAtA = null;
        for (int i = 0; i < 1800 && mainTrainAtA == null; i++) {
            engine.step(1);
            for (TrainState t : engine.trains()) {
                if (t.patternId().equals("L") && t.stop() == 0 && t.phase() != TrainPhase.RUNNING) {
                    mainTrainAtA = t.id();
                }
            }
        }
        assertThat(mainTrainAtA).isNotNull();
        // No-one bound for the branch rides a main-route train.
        assertThat(engine.trainPassengersTo(mainTrainAtA, "X")).isZero();
        assertThat(engine.trainPassengersTo(mainTrainAtA, "Y")).isZero();
    }

    @Test
    void theStationQueuesOfTheBranchAreSignedTowardsItsTerminal() {
        SimulationEngine engine = engine(HeadwayStrategy.NO_CONTROL);
        engine.run(300, 1);
        var x = engine.snapshot().stations().stream().filter(s -> s.id().equals("X")).findFirst().orElseThrow();
        assertThat(x.queues()).extracting(SimulationSnapshot.QueueView::towardsStationId)
                .containsExactlyInAnyOrder("Y", "A");
    }

    @Test
    void aBranchedLineRunsDeterministically() {
        SimulationEngine a = engine(HeadwayStrategy.BALANCED);
        SimulationEngine b = a.copy();
        a.run(1800, 1);
        b.run(1800, 1);
        assertThat(a.snapshot().metrics()).isEqualTo(b.snapshot().metrics());
    }
}
