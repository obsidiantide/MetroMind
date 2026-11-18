package com.metromind.sim;

import com.metromind.network.ServicePattern;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Regularity of the combined service on the trunk a branched line's patterns share. */
class TrunkRegularityTest {

    private static final double START = 7 * 3600;

    private static SimulationEngine branched(HeadwayStrategy strategy) {
        return new SimulationEngine(TestNetworks.branched(), START, strategy);
    }

    private static void run(SimulationEngine engine, int seconds) {
        for (int i = 0; i < seconds; i++) {
            engine.step(1);
        }
    }

    private static double cv(List<Double> times) {
        double[] gaps = new double[times.size() - 1];
        for (int i = 1; i < times.size(); i++) {
            gaps[i - 1] = times.get(i) - times.get(i - 1);
        }
        double mean = java.util.Arrays.stream(gaps).average().orElseThrow();
        double var = java.util.Arrays.stream(gaps).map(g -> (g - mean) * (g - mean)).average().orElseThrow();
        return Math.sqrt(var) / mean;
    }

    @Test
    void onlyBranchedLinesHaveATrunk() {
        assertThat(new SimulationEngine(TestNetworks.twoLines(), START, HeadwayStrategy.NO_CONTROL)
                .trunkHeadwayCv()).isEmpty();
        assertThat(branched(HeadwayStrategy.NO_CONTROL).trunkHeadwayCv()).containsOnlyKeys("L");
    }

    @Test
    void isZeroBeforeAnyTrainHasLeftTheJunction() {
        assertThat(branched(HeadwayStrategy.BALANCED).trunkHeadwayCv()).containsEntry("L", 0.0);
    }

    @Test
    void measuresTheRecentDeparturesOfEveryPatternFromTheJunction() {
        SimulationEngine engine = branched(HeadwayStrategy.NO_CONTROL);
        Map<String, ServicePattern> patterns = new HashMap<>();
        engine.network().line("L").patterns().forEach(p -> patterns.put(p.id(), p));
        Map<Integer, List<Double>> departures = Map.of(1, new ArrayList<>(), -1, new ArrayList<>());
        engine.injectDelay(engine.trains().get(0).id(), 240);
        for (int i = 0; i < 3600; i++) {
            Map<String, Integer> atJunction = new HashMap<>();
            for (TrainState t : engine.trains()) {
                ServicePattern p = patterns.get(t.patternId());
                if (t.phase() != TrainPhase.RUNNING && p.stationAtStop(t.stop()).equals("B")) {
                    atJunction.put(t.id(), p.directionAtStop(t.stop()));
                }
            }
            engine.step(1);
            atJunction.forEach((id, direction) -> {
                if (engine.train(id).phase() == TrainPhase.RUNNING) {
                    departures.get(direction).add(engine.simTime());
                }
            });
        }
        int n = SimulationEngine.TRUNK_SAMPLE_DEPARTURES;
        List<Double> outbound = departures.get(1);
        List<Double> inbound = departures.get(-1);
        double expected = (cv(outbound.subList(outbound.size() - n, outbound.size()))
                + cv(inbound.subList(inbound.size() - n, inbound.size()))) / 2;
        assertThat(engine.trunkHeadwayCv().get("L")).isCloseTo(expected, within(1e-9));
    }

    @Test
    void aBreakdownOnTheTrunkBunchesTheCombinedService() {
        SimulationEngine undisturbed = branched(HeadwayStrategy.NO_CONTROL);
        run(undisturbed, 1800);
        SimulationEngine disrupted = undisturbed.copy();
        disrupted.injectBreakdown(disrupted.trains().get(0).id(), 600);
        run(undisturbed, 1200);
        run(disrupted, 1200);
        assertThat(disrupted.trunkHeadwayCv().get("L"))
                .isGreaterThan(undisturbed.trunkHeadwayCv().get("L") + 0.2);
    }

    @Test
    void aCopyKeepsTheDepartureLog() {
        SimulationEngine engine = branched(HeadwayStrategy.BALANCED);
        run(engine, 1800);
        assertThat(engine.trunkHeadwayCv().get("L")).isPositive();
        assertThat(engine.copy().trunkHeadwayCv()).isEqualTo(engine.trunkHeadwayCv());
    }
}
