package com.metromind.incident;

import com.metromind.sim.ClosureResponse;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.TestNetworks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class IncidentImpactAnalysisTest {

    private static final double HORIZON = 1800;

    private static SimulationEngine live() {
        SimulationEngine live = new SimulationEngine(TestNetworks.singleLine(), 8 * 3600, HeadwayStrategy.NO_CONTROL);
        live.run(600, 1);
        return live;
    }

    @Test
    void stationClosureAddsWaitingAndLeavesTheLiveEngineAlone() {
        SimulationEngine live = live();
        double before = live.simTime();
        BenchmarkReport.Scenario closure = BenchmarkReport.Scenario.stationClosure("C", 900);

        IncidentImpactReport report = new DelayPropagationAnalyzer()
                .analyze(live, closure, HORIZON, HeadwayStrategy.all());

        assertThat(report.incident()).isEqualTo(closure);
        assertThat(report.horizonSeconds()).isEqualTo(HORIZON);
        assertThat(report.outcomes()).extracting(DelayImpactReport.StrategyOutcome::strategy)
                .containsExactlyElementsOf(HeadwayStrategy.all().stream().map(HeadwayStrategy::name).toList());
        assertThat(report.outcomes()).allSatisfy(o ->
                assertThat(o.totalExtraTravelMinutes()).as(o.strategy()).isPositive());
        assertThat(report.bestStrategy()).isEqualTo(report.outcomes().stream()
                .min((a, b) -> Double.compare(a.totalPassengerTravelMinutes(), b.totalPassengerTravelMinutes()))
                .orElseThrow().strategy());
        assertThat(live.simTime()).isEqualTo(before);
        assertThat(live.snapshot().stations()).noneSatisfy(s -> assertThat(s.closed()).isTrue());
    }

    @Test
    void sectionClosureDelaysTrainsUnderEveryResponse() {
        for (ClosureResponse response : ClosureResponse.values()) {
            BenchmarkReport.Scenario closure =
                    BenchmarkReport.Scenario.sectionClosure("L1", "C", "D", response, 600);
            IncidentImpactReport report = new DelayPropagationAnalyzer()
                    .analyze(live(), closure, HORIZON, List.of(HeadwayStrategy.NO_CONTROL));
            DelayImpactReport.StrategyOutcome outcome = report.outcomes().get(0);
            assertThat(outcome.trainsAffected()).as(response.name()).isPositive();
            assertThat(outcome.maxTrainDelaySeconds()).as(response.name()).isPositive();
        }
    }

    @Test
    void aSectionalPowerFailureHurtsLessThanLosingTheWholeLine() {
        BenchmarkReport.Scenario section = BenchmarkReport.Scenario.sectionalPowerFailure("L1", "D", "F", 600);
        BenchmarkReport.Scenario line = BenchmarkReport.Scenario.powerFailure("L1", 600);
        DelayPropagationAnalyzer analyzer = new DelayPropagationAnalyzer();
        DelayImpactReport.StrategyOutcome sectional = analyzer
                .analyze(live(), section, HORIZON, List.of(HeadwayStrategy.NO_CONTROL)).outcomes().get(0);
        DelayImpactReport.StrategyOutcome whole = analyzer
                .analyze(live(), line, HORIZON, List.of(HeadwayStrategy.NO_CONTROL)).outcomes().get(0);

        assertThat(sectional.totalExtraTravelMinutes()).isPositive()
                .isLessThan(whole.totalExtraTravelMinutes());
        assertThat(sectional.trainsAffected()).isPositive();
    }

    @Test
    void aDelayScenarioMatchesTheDelayAnalysis() {
        DelayPropagationAnalyzer analyzer = new DelayPropagationAnalyzer();
        DelayImpactReport delay = analyzer.analyze(live(), "L1-2", 240, HORIZON, HeadwayStrategy.all());
        IncidentImpactReport incident = analyzer.analyze(live(),
                BenchmarkReport.Scenario.train(BenchmarkReport.IncidentKind.DELAY, "L1-2", 240), HORIZON,
                HeadwayStrategy.all());

        assertThat(incident.bestStrategy()).isEqualTo(delay.bestStrategy());
        for (int i = 0; i < delay.outcomes().size(); i++) {
            assertThat(incident.outcomes().get(i).totalPassengerTravelMinutes())
                    .isCloseTo(delay.outcomes().get(i).totalPassengerTravelMinutes(), within(1e-9));
        }
    }

    @Test
    void invalidIncidentsAreRejectedBeforeAnyRun() {
        DelayPropagationAnalyzer analyzer = new DelayPropagationAnalyzer();
        assertThatThrownBy(() -> analyzer.analyze(live(), BenchmarkReport.Scenario.stationClosure("NOPE", 600),
                HORIZON, HeadwayStrategy.all())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analyzer.analyze(live(),
                BenchmarkReport.Scenario.sectionClosure("L1", "A", "C", ClosureResponse.WAIT, 600),
                HORIZON, HeadwayStrategy.all())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closureWhatIfIsDeterministic() {
        BenchmarkReport.Scenario closure = BenchmarkReport.Scenario.stationClosure("D", 600);
        SimulationEngine live = live();
        IncidentImpactReport a = new DelayPropagationAnalyzer().analyze(live, closure, HORIZON, HeadwayStrategy.all());
        IncidentImpactReport b = new DelayPropagationAnalyzer().analyze(live, closure, HORIZON, HeadwayStrategy.all());
        assertThat(a).isEqualTo(b);
    }
}
