package com.metromind.sim;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTurnSkipStopTest {

    private static final double START = 8 * 3600;
    private static final double WARM_UP = 900;

    private static SimulationEngine delayed(HeadwayStrategy strategy, double seconds) {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLineWithQuietStop(), START, strategy);
        engine.run(WARM_UP, 1);
        engine.injectDelay("L1-2", seconds);
        return engine;
    }

    @Test
    void isOfferedAndUsesBothRecoveryTools() {
        HeadwayStrategy s = HeadwayStrategy.byName("short_turn_skip_stop");
        assertThat(s).isSameAs(HeadwayStrategy.SHORT_TURN_SKIP_STOP);
        assertThat(s.shortTurnsLateTrains()).isTrue();
        assertThat(s.skipsStopsForLateTrains()).isTrue();
        assertThat(s.holdSeconds(100, 300, 200)).isEqualTo(HeadwayStrategy.BALANCED.holdSeconds(100, 300, 200));
    }

    @Test
    void aVeryLateTrainIsShortTurnedRatherThanSkippingStops() {
        SimulationEngine combined = delayed(HeadwayStrategy.SHORT_TURN_SKIP_STOP, 420);
        SimulationEngine shortTurn = delayed(HeadwayStrategy.SHORT_TURN, 420);
        combined.run(1800, 1);
        shortTurn.run(1800, 1);

        // It turns back exactly where short-turning alone would turn it ...
        assertThat(combined.shortTurns()).isNotEmpty().isEqualTo(shortTurn.shortTurns());
        // ... instead of running through the quiet stop as skip-stop alone does.
        assertThat(combined.skippedStops()).noneSatisfy(skip -> assertThat(skip.trainId()).isEqualTo("L1-2"));
        assertThat(combined.skippedStops()).allSatisfy(skip ->
                assertThat(skip.latenessSeconds()).isLessThan(SimulationEngine.SHORT_TURN_LATENESS_SECONDS));
    }

    @Test
    void aModeratelyLateTrainSkipsStopsLikeSkipStop() {
        SimulationEngine combined = delayed(HeadwayStrategy.SHORT_TURN_SKIP_STOP, 180);
        SimulationEngine skipStop = delayed(HeadwayStrategy.SKIP_STOP, 180);
        combined.run(1800, 1);
        skipStop.run(1800, 1);

        assertThat(combined.shortTurns()).isEmpty();
        assertThat(combined.skippedStops()).isNotEmpty().isEqualTo(skipStop.skippedStops());
        // Short turns never come into play, so the two runs are identical.
        assertThat(combined.snapshot().metrics()).isEqualTo(skipStop.snapshot().metrics());
    }

    @Test
    void isDeterministic() {
        SimulationEngine a = delayed(HeadwayStrategy.SHORT_TURN_SKIP_STOP, 420);
        SimulationEngine b = a.copy();
        a.run(1800, 1);
        b.run(1800, 1);
        assertThat(a.snapshot().metrics()).isEqualTo(b.snapshot().metrics());
        assertThat(a.shortTurns()).isEqualTo(b.shortTurns());
        assertThat(a.skippedStops()).isEqualTo(b.skippedStops());
    }
}
