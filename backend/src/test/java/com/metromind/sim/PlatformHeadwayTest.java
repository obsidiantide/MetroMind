package com.metromind.sim;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PlatformHeadwayTest {

    private static final double START = 8 * 3600;
    /** Main pattern A–B–C–D and back: 2 × (100 + 110 + 120) s running plus six 25 s dwells. */
    private static final double MAIN_LOOP = 810;
    /** Branch pattern A–B–X–Y and back: 2 × (100 + 90 + 80) s running plus six 25 s dwells. */
    private static final double BRANCH_LOOP = 690;

    @Test
    void onAnUnbranchedLinePlatformHeadwaysAreThePatternHeadways() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, HeadwayStrategy.NO_CONTROL);
        engine.injectDelay("L1-2", 90);
        for (int i = 0; i < 1200; i++) {
            engine.step(1);
            for (TrainState t : engine.trains()) {
                if (t.phase() != TrainPhase.RUNNING) {
                    assertThat(engine.platformForwardHeadway(t)).isCloseTo(engine.forwardHeadway(t), within(1e-6));
                    assertThat(engine.platformBackwardHeadway(t)).isCloseTo(engine.backwardHeadway(t), within(1e-6));
                }
            }
        }
    }

    @Test
    void theTrunkIsServedByTheTrainsOfBothPatterns() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.branched(), START, HeadwayStrategy.NO_CONTROL);
        double trunk = 1 / (4 / MAIN_LOOP + 2 / BRANCH_LOOP);
        boolean sawTrunk = false;
        boolean sawMainOnly = false;
        boolean sawBranchOnly = false;
        for (int i = 0; i < 900; i++) {
            engine.step(1);
            for (TrainState t : engine.trains()) {
                if (t.phase() == TrainPhase.RUNNING) {
                    continue;
                }
                String station = engine.network().line("L").patterns().stream()
                        .filter(p -> p.id().equals(t.patternId())).findFirst().orElseThrow()
                        .stationAtStop(t.stop());
                switch (station) {
                    case "A", "B" -> {
                        sawTrunk = true;
                        assertThat(engine.platformEvenHeadway(t)).isCloseTo(trunk, within(1e-6));
                        // Trains of the other pattern run in between, so the gaps are never longer than its own.
                        assertThat(engine.platformForwardHeadway(t)).isLessThanOrEqualTo(engine.forwardHeadway(t));
                        assertThat(engine.platformBackwardHeadway(t)).isLessThanOrEqualTo(engine.backwardHeadway(t));
                    }
                    case "C", "D" -> {
                        sawMainOnly = true;
                        assertThat(engine.platformEvenHeadway(t)).isCloseTo(MAIN_LOOP / 4, within(1e-6));
                        assertThat(engine.platformForwardHeadway(t)).isCloseTo(engine.forwardHeadway(t), within(1e-6));
                    }
                    default -> {
                        sawBranchOnly = true;
                        assertThat(engine.platformEvenHeadway(t)).isCloseTo(BRANCH_LOOP / 2, within(1e-6));
                        assertThat(engine.platformForwardHeadway(t)).isCloseTo(engine.forwardHeadway(t), within(1e-6));
                    }
                }
            }
        }
        assertThat(sawTrunk).isTrue();
        assertThat(sawMainOnly).isTrue();
        assertThat(sawBranchOnly).isTrue();
    }

    @Test
    void aTrainOfTheOtherPatternJustAheadShortensTheGapOnTheTrunk() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.branched(), START, HeadwayStrategy.NO_CONTROL);
        TrainState branchTrain = null;
        for (int i = 0; i < 1800 && branchTrain == null; i++) {
            engine.step(1);
            for (TrainState t : engine.trains()) {
                if (t.patternId().equals("L/Y") && t.stop() == 0 && t.phase() != TrainPhase.RUNNING) {
                    branchTrain = t;
                }
            }
        }
        assertThat(branchTrain).isNotNull();
        // At the first terminal, with four main trains and two branch trains, the nearest train ahead of a branch
        // train is a main-route train well inside the branch pattern's own headway.
        assertThat(engine.platformForwardHeadway(branchTrain)).isLessThan(engine.forwardHeadway(branchTrain));
    }
}
