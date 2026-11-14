package com.metromind.incident;

import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.TestNetworks;
import com.metromind.sim.TrainPhase;
import com.metromind.sim.TrainState;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DelayPropagationAnalyzerTest {

    @Test
    void fourMinuteDelayPropagatesToFollowersAndStations() {
        SimulationEngine live = new SimulationEngine(TestNetworks.singleLine(), 8 * 3600, HeadwayStrategy.NO_CONTROL);
        live.run(600, 1);

        DelayImpactReport report = new DelayPropagationAnalyzer()
                .analyze(live, "L1-2", 240, 1800, HeadwayStrategy.all());

        assertThat(report.outcomes()).hasSize(3);
        DelayImpactReport.StrategyOutcome none = report.outcomes().get(0);
        assertThat(none.strategy()).isEqualTo("NO_CONTROL");
        assertThat(none.trains().get(0).trainId()).isEqualTo("L1-2");
        assertThat(none.maxTrainDelaySeconds()).isGreaterThanOrEqualTo(200);
        assertThat(none.totalExtraPassengerWaitMinutes()).isPositive();
        assertThat(none.totalExtraTravelMinutes())
                .isEqualTo(none.totalExtraPassengerWaitMinutes() + none.totalExtraRideMinutes());
        assertThat(report.bestStrategy()).isNotNull();
    }

    @Test
    void trainStuckBetweenStationsKeepsItsPassengersWaitingOnBoard() {
        SimulationEngine live = new SimulationEngine(TestNetworks.singleLine(), 8 * 3600, HeadwayStrategy.NO_CONTROL);
        live.run(900, 1);
        String loaded = live.trains().stream().filter(t -> t.phase() == TrainPhase.RUNNING)
                .max(Comparator.comparingDouble(TrainState::passengers))
                .orElseThrow().id();

        DelayImpactReport.StrategyOutcome outcome = new DelayPropagationAnalyzer()
                .analyze(live, loaded, 300, 1800, List.of(HeadwayStrategy.NO_CONTROL)).outcomes().get(0);

        assertThat(outcome.totalExtraRideMinutes()).isPositive();
        assertThat(outcome.totalExtraTravelMinutes()).isGreaterThan(outcome.totalExtraPassengerWaitMinutes());
    }

    @Test
    void analysisDoesNotMutateLiveEngine() {
        SimulationEngine live = new SimulationEngine(TestNetworks.singleLine(), 8 * 3600, HeadwayStrategy.NO_CONTROL);
        live.run(300, 1);
        var before = live.snapshot().trains();
        new DelayPropagationAnalyzer().analyze(live, "L1-1", 240, 900, List.of(HeadwayStrategy.NO_CONTROL));
        assertThat(live.snapshot().trains()).isEqualTo(before);
        assertThat(live.simTime()).isEqualTo(8 * 3600 + 300);
    }

    @Test
    void extraDelayCountsMissingArrivals() {
        assertThat(DelayPropagationAnalyzer.extraDelay(List.of(10.0, 20.0), List.of(15.0, 30.0), 40)).isEqualTo(10);
        assertThat(DelayPropagationAnalyzer.extraDelay(List.of(10.0, 20.0, 30.0), List.of(15.0), 100)).isEqualTo(80);
        assertThat(DelayPropagationAnalyzer.extraDelay(List.of(), List.of(), 100)).isZero();
    }
}
