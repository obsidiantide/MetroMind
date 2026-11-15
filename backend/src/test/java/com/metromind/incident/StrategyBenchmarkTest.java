package com.metromind.incident;

import com.metromind.sim.ClosureResponse;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.SimulationSnapshot;
import com.metromind.sim.TestNetworks;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyBenchmarkTest {

    private static SimulationEngine live() {
        SimulationEngine live = new SimulationEngine(TestNetworks.singleLine(), 8 * 3600, HeadwayStrategy.NO_CONTROL);
        live.run(300, 1);
        return live;
    }

    @Test
    void ranksEveryStrategyBestFirst() {
        BenchmarkReport report = new StrategyBenchmark().run(live(), 4, 42, 1200, HeadwayStrategy.all());

        assertThat(report.scenarios()).hasSize(4);
        assertThat(report.rankings()).hasSize(HeadwayStrategy.all().size());
        assertThat(report.rankings()).extracting(BenchmarkReport.StrategyRanking::rank)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, HeadwayStrategy.all().size()).boxed().toList());
        assertThat(report.rankings()).extracting(BenchmarkReport.StrategyRanking::strategy)
                .containsExactlyInAnyOrderElementsOf(HeadwayStrategy.all().stream().map(HeadwayStrategy::name).toList());
        assertThat(report.rankings()).extracting(BenchmarkReport.StrategyRanking::meanWaitSeconds).isSorted();
        assertThat(report.rankings()).allSatisfy(r -> {
            assertThat(r.waitSecondsPerScenario()).hasSize(4).allSatisfy(w -> assertThat(w).isPositive());
            assertThat(r.p95WaitSeconds()).isGreaterThanOrEqualTo(r.meanWaitSeconds() - 1e-9);
            assertThat(r.meanTravelMinutes()).isPositive();
        });
    }

    @Test
    void countsScenariosRecoveredFromAndAveragesTheirRecoveryTime() {
        BenchmarkReport report = new StrategyBenchmark().run(live(), 4, 7, 2400,
                List.of(HeadwayStrategy.NO_CONTROL, HeadwayStrategy.BALANCED));

        assertThat(report.rankings()).allSatisfy(r -> {
            assertThat(r.unrecoveredScenarios()).isBetween(0, 4);
            if (r.unrecoveredScenarios() == 4) {
                assertThat(r.meanRecoverySeconds()).isNull();
            } else {
                assertThat(r.meanRecoverySeconds()).isBetween(0.0, 2400.0);
            }
        });
    }

    @Test
    void delayScenarioRecoversLikeTheSameWhatIfAnalysis() {
        SimulationEngine live = live();
        long seed = 0;
        while (StrategyBenchmark.drawScenarios(live, 1, seed).get(0).kind() != BenchmarkReport.IncidentKind.DELAY) {
            seed++;
        }
        BenchmarkReport.Scenario scenario = StrategyBenchmark.drawScenarios(live, 1, seed).get(0);

        BenchmarkReport.StrategyRanking ranking = new StrategyBenchmark()
                .run(live, 1, seed, 3600, List.of(HeadwayStrategy.BALANCED)).rankings().get(0);
        DelayImpactReport.StrategyOutcome whatIf = new DelayPropagationAnalyzer()
                .analyze(live, scenario.trainId(), scenario.seconds(), 3600, List.of(HeadwayStrategy.BALANCED))
                .outcomes().get(0);

        assertThat(ranking.meanRecoverySeconds()).isEqualTo(whatIf.headwayRecoverySeconds());
        assertThat(ranking.unrecoveredScenarios()).isEqualTo(whatIf.headwayRecoverySeconds() == null ? 1 : 0);
    }

    @Test
    void sameSeedAndStateGiveTheSameReport() {
        SimulationEngine live = live();
        List<HeadwayStrategy> two = List.of(HeadwayStrategy.NO_CONTROL, HeadwayStrategy.SHORT_TURN);
        BenchmarkReport first = new StrategyBenchmark().run(live, 3, 7, 900, two);
        BenchmarkReport second = new StrategyBenchmark().run(live, 3, 7, 900, two);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void scenariosDependOnTheSeedAndStayInRange() {
        SimulationEngine live = live();
        List<BenchmarkReport.Scenario> a = StrategyBenchmark.drawScenarios(live, 20, 1);
        List<BenchmarkReport.Scenario> b = StrategyBenchmark.drawScenarios(live, 20, 2);
        assertThat(a).isNotEqualTo(b);
        assertThat(a).extracting(BenchmarkReport.Scenario::kind)
                .contains(BenchmarkReport.IncidentKind.values());
        assertThat(a).allSatisfy(s -> {
            switch (s.kind()) {
                case DELAY -> {
                    assertThat(s.trainId()).isIn("L1-1", "L1-2", "L1-3", "L1-4");
                    assertThat(s.seconds()).isBetween(StrategyBenchmark.MIN_DELAY_SECONDS, StrategyBenchmark.MAX_DELAY_SECONDS);
                }
                case BREAKDOWN -> {
                    assertThat(s.trainId()).isIn("L1-1", "L1-2", "L1-3", "L1-4");
                    assertThat(s.seconds()).isBetween(StrategyBenchmark.MIN_REPAIR_SECONDS, StrategyBenchmark.MAX_REPAIR_SECONDS);
                }
                case SECTION_CLOSURE, STATION_CLOSURE -> assertThat(s.seconds())
                        .isBetween(StrategyBenchmark.MIN_CLOSURE_SECONDS, StrategyBenchmark.MAX_CLOSURE_SECONDS);
            }
        });
    }

    @Test
    void closureScenariosNameAdjacentStationsOrAStationOfTheNetwork() {
        SimulationEngine live = new SimulationEngine(TestNetworks.twoLines(), 8 * 3600, HeadwayStrategy.NO_CONTROL);
        List<BenchmarkReport.Scenario> scenarios = StrategyBenchmark.drawScenarios(live, 40, 3,
                EnumSet.of(BenchmarkReport.IncidentKind.SECTION_CLOSURE, BenchmarkReport.IncidentKind.STATION_CLOSURE));
        assertThat(scenarios).extracting(BenchmarkReport.Scenario::kind)
                .containsOnly(BenchmarkReport.IncidentKind.SECTION_CLOSURE, BenchmarkReport.IncidentKind.STATION_CLOSURE)
                .contains(BenchmarkReport.IncidentKind.SECTION_CLOSURE, BenchmarkReport.IncidentKind.STATION_CLOSURE);
        assertThat(scenarios).extracting(BenchmarkReport.Scenario::lineId).contains("L1", "L2");
        assertThat(scenarios).allSatisfy(s -> {
            assertThat(s.trainId()).isNull();
            if (s.kind() == BenchmarkReport.IncidentKind.SECTION_CLOSURE) {
                List<String> ids = live.network().line(s.lineId()).stationIds();
                int from = ids.indexOf(s.fromStationId());
                assertThat(from).isNotNegative();
                assertThat(ids.indexOf(s.toStationId())).isEqualTo(from + 1);
                assertThat(s.response()).isNotNull();
                assertThat(s.stationId()).isNull();
            } else {
                assertThat(s.stationId()).isIn("A", "B", "C", "D", "E", "F", "X", "Y");
                assertThat(s.lineId()).isNull();
            }
        });
        assertThat(scenarios).extracting(BenchmarkReport.Scenario::response)
                .contains(ClosureResponse.WAIT, ClosureResponse.TURN_BACK);
    }

    @Test
    void onlyTheRequestedKindsAreDrawn() {
        List<BenchmarkReport.Scenario> delays = StrategyBenchmark.drawScenarios(live(), 10, 1,
                EnumSet.of(BenchmarkReport.IncidentKind.DELAY));
        assertThat(delays).extracting(BenchmarkReport.Scenario::kind).containsOnly(BenchmarkReport.IncidentKind.DELAY);
        assertThatThrownBy(() -> StrategyBenchmark.drawScenarios(live(), 1, 1,
                EnumSet.noneOf(BenchmarkReport.IncidentKind.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closureScenariosAreRunInsteadOfTrainIncidents() {
        SimulationEngine live = live();
        Set<BenchmarkReport.IncidentKind> trains = EnumSet.of(BenchmarkReport.IncidentKind.DELAY);
        BenchmarkReport closures = new StrategyBenchmark().run(live, 3, 9, 1200, List.of(HeadwayStrategy.BALANCED),
                EnumSet.of(BenchmarkReport.IncidentKind.SECTION_CLOSURE, BenchmarkReport.IncidentKind.STATION_CLOSURE));
        BenchmarkReport delays = new StrategyBenchmark().run(live, 3, 9, 1200, List.of(HeadwayStrategy.BALANCED),
                trains);
        assertThat(closures.scenarios()).noneMatch(s -> s.kind() == BenchmarkReport.IncidentKind.DELAY);
        assertThat(closures.rankings().get(0).waitSecondsPerScenario()).hasSize(3)
                .allSatisfy(w -> assertThat(w).isPositive());
        // Same seed, different kinds: different incidents, so different results.
        assertThat(closures.rankings().get(0).meanWaitSeconds())
                .isNotEqualTo(delays.rankings().get(0).meanWaitSeconds());
    }

    @Test
    void closureScenariosAreDeterministicAndLeaveTheLiveEngineOpen() {
        SimulationEngine live = live();
        Set<BenchmarkReport.IncidentKind> kinds =
                EnumSet.of(BenchmarkReport.IncidentKind.SECTION_CLOSURE, BenchmarkReport.IncidentKind.STATION_CLOSURE);
        List<HeadwayStrategy> two = List.of(HeadwayStrategy.BALANCED, HeadwayStrategy.SKIP_STOP);
        BenchmarkReport first = new StrategyBenchmark().run(live, 4, 21, 900, two, kinds);
        BenchmarkReport second = new StrategyBenchmark().run(live, 4, 21, 900, two, kinds);
        assertThat(second).isEqualTo(first);
        assertThat(live.snapshot().closedSections()).isEmpty();
        assertThat(live.snapshot().stations()).noneMatch(SimulationSnapshot.StationView::closed);
    }

    @Test
    void benchmarkLeavesTheLiveEngineUntouched() {
        SimulationEngine live = live();
        double time = live.simTime();
        double ride = live.passengerRideSeconds();
        new StrategyBenchmark().run(live, 2, 3, 600, List.of(HeadwayStrategy.BALANCED));
        assertThat(live.simTime()).isEqualTo(time);
        assertThat(live.passengerRideSeconds()).isEqualTo(ride);
        assertThat(live.strategy()).isEqualTo(HeadwayStrategy.NO_CONTROL);
        assertThat(live.train("L1-1").delaySeconds()).isLessThan(60);
    }

    @Test
    void identicalResultsAreRankedByName() {
        // Without spare trains in the depot, adaptive dispatching behaves exactly like balancing.
        BenchmarkReport report = new StrategyBenchmark().run(live(), 2, 5, 600,
                List.of(HeadwayStrategy.BALANCED, HeadwayStrategy.ADAPTIVE_DISPATCH));
        assertThat(report.rankings().get(0).meanWaitSeconds()).isEqualTo(report.rankings().get(1).meanWaitSeconds());
        assertThat(report.rankings()).extracting(BenchmarkReport.StrategyRanking::strategy)
                .containsExactly("ADAPTIVE_DISPATCH", "BALANCED");
    }

    @Test
    void shortTurnsAreCountedPerStrategyAndByStation() {
        BenchmarkReport report = new StrategyBenchmark().run(live(), 6, 2, 1800,
                List.of(HeadwayStrategy.NO_CONTROL, HeadwayStrategy.SHORT_TURN),
                EnumSet.of(BenchmarkReport.IncidentKind.DELAY, BenchmarkReport.IncidentKind.BREAKDOWN));
        BenchmarkReport.StrategyRanking shortTurn = report.rankings().stream()
                .filter(r -> r.strategy().equals("SHORT_TURN")).findFirst().orElseThrow();
        BenchmarkReport.StrategyRanking noControl = report.rankings().stream()
                .filter(r -> r.strategy().equals("NO_CONTROL")).findFirst().orElseThrow();

        assertThat(noControl.meanShortTurns()).isZero();
        assertThat(noControl.shortTurnsByStation()).isEmpty();
        assertThat(shortTurn.meanShortTurns()).isPositive();
        int total = shortTurn.shortTurnsByStation().values().stream().mapToInt(Integer::intValue).sum();
        assertThat(total).isEqualTo((int) Math.round(shortTurn.meanShortTurns() * 6));
        // Terminals are never short-turn points.
        assertThat(shortTurn.shortTurnsByStation()).doesNotContainKeys("A", "F");
        assertThat(List.copyOf(shortTurn.shortTurnsByStation().values())).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void skippedStopsAreCountedPerStrategyAndByStation() {
        SimulationEngine live = new SimulationEngine(TestNetworks.singleLineWithQuietStop(), 8 * 3600,
                HeadwayStrategy.NO_CONTROL);
        live.run(900, 1);
        BenchmarkReport report = new StrategyBenchmark().run(live, 6, 2, 1800,
                List.of(HeadwayStrategy.BALANCED, HeadwayStrategy.SKIP_STOP),
                EnumSet.of(BenchmarkReport.IncidentKind.DELAY));
        BenchmarkReport.StrategyRanking skipStop = report.rankings().stream()
                .filter(r -> r.strategy().equals("SKIP_STOP")).findFirst().orElseThrow();
        BenchmarkReport.StrategyRanking balanced = report.rankings().stream()
                .filter(r -> r.strategy().equals("BALANCED")).findFirst().orElseThrow();

        assertThat(balanced.meanStopsSkipped()).isZero();
        assertThat(balanced.meanPassengersCarriedPast()).isZero();
        assertThat(balanced.stopsSkippedByStation()).isEmpty();
        assertThat(skipStop.meanStopsSkipped()).isPositive();
        assertThat(skipStop.meanPassengersCarriedPast()).isPositive();
        int total = skipStop.stopsSkippedByStation().values().stream().mapToInt(Integer::intValue).sum();
        assertThat(total).isEqualTo((int) Math.round(skipStop.meanStopsSkipped() * 6));
        // Terminals are never skipped.
        assertThat(skipStop.stopsSkippedByStation()).doesNotContainKeys("A", "F");
        assertThat(List.copyOf(skipStop.stopsSkippedByStation().values())).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void stationCountsAreOrderedMostFirstThenById() {
        Map<String, Integer> ordered = StrategyBenchmark.mostFirst(Map.of("C", 1, "B", 3, "A", 1, "D", 2));
        assertThat(ordered.keySet()).containsExactly("B", "D", "A", "C");
    }

    @Test
    void percentileUsesTheNearestRank() {
        List<Double> values = List.of(5.0, 1.0, 4.0, 2.0, 3.0);
        assertThat(StrategyBenchmark.percentile(values, 0.95)).isEqualTo(5.0);
        assertThat(StrategyBenchmark.percentile(values, 0.5)).isEqualTo(3.0);
        assertThat(StrategyBenchmark.percentile(List.of(7.0), 0.95)).isEqualTo(7.0);
        assertThat(StrategyBenchmark.percentile(List.of(), 0.95)).isZero();
    }

    @Test
    void rejectsAnEmptyBenchmark() {
        assertThatThrownBy(() -> new StrategyBenchmark().run(live(), 0, 1, 600, HeadwayStrategy.all()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
