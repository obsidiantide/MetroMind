package com.metromind.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metromind.network.MetroNetwork;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationEngine;
import com.metromind.sim.TestNetworks;
import com.metromind.sim.TrainPhase;
import com.metromind.sim.TrainState;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
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
    void bestStrategyHasTheShortestTotalTravelTime() {
        SimulationEngine live = new SimulationEngine(TestNetworks.singleLine(), 8 * 3600, HeadwayStrategy.NO_CONTROL);
        live.run(600, 1);

        DelayImpactReport report = new DelayPropagationAnalyzer()
                .analyze(live, "L1-3", 240, 1800, HeadwayStrategy.all());

        DelayImpactReport.StrategyOutcome best = report.outcomes().stream()
                .min(Comparator.comparingDouble(DelayImpactReport.StrategyOutcome::totalPassengerTravelMinutes))
                .orElseThrow();
        assertThat(report.bestStrategy()).isEqualTo(best.strategy());
        for (DelayImpactReport.StrategyOutcome o : report.outcomes()) {
            assertThat(o.totalPassengerTravelMinutes()).isGreaterThan(o.totalPassengerWaitMinutes());
        }
    }

    /**
     * Regression: on the seed network a one-minute delay of RED-4 at 08:10 used to report about 375
     * passenger-minutes <em>less</em> platform waiting than the baseline. The passengers had not been spared any
     * time: the bunched trains (they started snapped to platforms) picked them up sooner and they spent the
     * difference, and more, on board. Counting on-board time, a delay costs passengers time on average; a single
     * delay may still come out marginally ahead when it happens to rebalance load between neighbouring trains,
     * but never by more than a rounding error of total travel time.
     */
    @Test
    void shortDelayCostsPassengersTimeOnTheSeedNetwork() throws Exception {
        MetroNetwork network;
        try (InputStream in = getClass().getResourceAsStream("/network/metropolis.json")) {
            network = new ObjectMapper().readValue(in, MetroNetwork.class);
        }
        for (double start : new double[] {8 * 3600, 17.5 * 3600}) {
            SimulationEngine live = new SimulationEngine(network, start, HeadwayStrategy.NO_CONTROL);
            live.run(600, 1);
            double totalExtra = 0;
            for (TrainState train : live.trains()) {
                DelayImpactReport.StrategyOutcome outcome = new DelayPropagationAnalyzer()
                        .analyze(live, train.id(), 60, 1800, List.of(HeadwayStrategy.NO_CONTROL))
                        .outcomes().get(0);
                assertThat(outcome.totalExtraTravelMinutes())
                        .as("delaying %s at %s", train.id(), start)
                        .isGreaterThan(-0.001 * outcome.totalPassengerTravelMinutes());
                totalExtra += outcome.totalExtraTravelMinutes();
            }
            assertThat(totalExtra / live.trains().size()).as("mean cost at %s", start).isGreaterThan(10);
        }
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
