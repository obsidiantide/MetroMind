package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Skip-stop on the trunk a branched line's patterns share ({@link TestNetworks#longTrunk()}: A–B–C). */
class TrunkSkipStopTest {

    private static final Set<String> TRUNK = Set.of("A", "B", "C");

    @Test
    void aTrainOnlySkipsATrunkStationWhenTheNextTrainOfAnyPatternIsCloseBehind() {
        SimulationEngine live = new SimulationEngine(TestNetworks.longTrunk(), 8 * 3600, HeadwayStrategy.SKIP_STOP);
        live.run(600, 1);
        int trunkSkips = 0;
        for (TrainState victim : live.trains()) {
            for (double delay : new double[] {180, 300, 420}) {
                SimulationEngine engine = live.copy();
                engine.injectDelay(victim.id(), delay);
                for (int i = 0; i < 3600; i++) {
                    int before = engine.skippedStops().size();
                    engine.step(1);
                    for (SkippedStop skip : engine.skippedStops().subList(before, engine.skippedStops().size())) {
                        if (!TRUNK.contains(skip.stationId())) {
                            continue;
                        }
                        trunkSkips++;
                        TrainState t = engine.train(skip.trainId());
                        // Other trains moved on by at most a second within the step that logged the skip.
                        assertThat(engine.platformBackwardHeadway(t))
                                .as("%s skipping %s", skip.trainId(), skip.stationId())
                                .isLessThan(SimulationEngine.SKIP_STOP_FOLLOWER_FRACTION
                                        * engine.platformForwardHeadway(t) + 2);
                    }
                }
            }
        }
        assertThat(trunkSkips).isPositive();
    }

    @Test
    void aBranchTrainLaggingOnlyWithinItsOwnPatternKeepsCallingOnTheTrunk() {
        // L-7 runs to Y. Judged against its own pattern alone it lags (the only other Y train is half a loop
        // away), so it used to run through B; in the combined service the next train is not close, so it calls.
        SimulationEngine engine = new SimulationEngine(TestNetworks.longTrunk(), 8 * 3600, HeadwayStrategy.SKIP_STOP);
        engine.run(600, 1);
        engine.injectDelay("L-7", 300);
        engine.run(3600, 1);
        assertThat(engine.skippedStops()).noneMatch(s -> s.trainId().equals("L-7") && TRUNK.contains(s.stationId()));
    }
}
