package com.metromind.incident;

import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.TestNetworks;
import org.junit.jupiter.api.Test;

import java.util.List;

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
                .containsExactly(1, 2, 3, 4, 5);
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
                .contains(BenchmarkReport.IncidentKind.DELAY, BenchmarkReport.IncidentKind.BREAKDOWN);
        assertThat(a).allSatisfy(s -> {
            assertThat(s.trainId()).isIn("L1-1", "L1-2", "L1-3", "L1-4");
            if (s.kind() == BenchmarkReport.IncidentKind.DELAY) {
                assertThat(s.seconds()).isBetween(StrategyBenchmark.MIN_DELAY_SECONDS, StrategyBenchmark.MAX_DELAY_SECONDS);
            } else {
                assertThat(s.seconds()).isBetween(StrategyBenchmark.MIN_REPAIR_SECONDS, StrategyBenchmark.MAX_REPAIR_SECONDS);
            }
        });
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
