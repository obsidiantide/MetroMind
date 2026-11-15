package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SkipStopTest {

    private static final double START = 8 * 3600;
    /** Long enough for trains to fill up, so a skipping train carries passengers for the stop it skips. */
    private static final double WARM_UP = 900;

    private static SimulationEngine delayed(HeadwayStrategy strategy) {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLineWithQuietStop(), START, strategy);
        engine.run(WARM_UP, 1);
        engine.injectDelay("L1-2", 240);
        return engine;
    }

    private static List<SimEvent> skips(SimulationEngine engine) {
        return engine.snapshot().recentEvents().stream()
                .filter(e -> e.type() == SimEvent.Type.TRAIN_SKIPPED_STOP).toList();
    }

    @Test
    void onlySkipStopStrategiesRunThroughStations() {
        assertThat(HeadwayStrategy.all()).filteredOn(HeadwayStrategy::skipsStopsForLateTrains)
                .containsExactly(HeadwayStrategy.SKIP_STOP, HeadwayStrategy.SHORT_TURN_SKIP_STOP);
        for (HeadwayStrategy s : HeadwayStrategy.all()) {
            if (!s.skipsStopsForLateTrains()) {
                SimulationEngine engine = delayed(s);
                engine.run(1800, 1);
                assertThat(skips(engine)).as(s.name()).isEmpty();
                assertThat(engine.snapshot().metrics().stopsSkipped()).as(s.name()).isZero();
                assertThat(engine.skippedStops()).as(s.name()).isEmpty();
            }
        }
    }

    @Test
    void lateTrainRunsThroughAQuietIntermediateStop() {
        SimulationEngine engine = delayed(HeadwayStrategy.SKIP_STOP);
        engine.run(1800, 1);
        assertThat(skips(engine)).isNotEmpty().allSatisfy(e -> {
            assertThat(e.subjectId()).isEqualTo("L1-2");
            // Terminals are never skipped: every train has to turn round there.
            assertThat(e.message()).doesNotContain("through A").doesNotContain("through F");
        });
    }

    @Test
    void skippingWinsBackLatenessComparedWithBalancing() {
        SimulationEngine skipStop = delayed(HeadwayStrategy.SKIP_STOP);
        SimulationEngine balanced = delayed(HeadwayStrategy.BALANCED);
        skipStop.run(1800, 1);
        balanced.run(1800, 1);
        var skip = skipStop.snapshot().metrics();
        var bal = balanced.snapshot().metrics();
        assertThat(skip.maxDelaySeconds()).isLessThan(bal.maxDelaySeconds());
        assertThat(skip.averageDelaySeconds()).isLessThan(bal.averageDelaySeconds());
    }

    @Test
    void passengersForASkippedStationComeBackFromTheNextStop() {
        SimulationEngine engine = delayed(HeadwayStrategy.SKIP_STOP);
        int guard = 0;
        while (skips(engine).isEmpty() && guard++ < 1800) {
            engine.step(1);
        }
        assertThat(skips(engine)).singleElement().satisfies(e -> assertThat(e.message()).contains("through D"));
        TrainState train = engine.train("L1-2");
        double carriedPast = engine.trainPassengersTo("L1-2", "D");
        assertThat(carriedPast).isPositive();
        int skippedStop = train.stop();
        while (engine.train("L1-2").stop() == skippedStop) {
            engine.step(1);
        }
        // At E they get off and queue for a train back towards A, which calls at D.
        assertThat(engine.trainPassengersTo("L1-2", "D")).isZero();
        assertThat(engine.stationWaiting("E", "L1", -1, "D")).isGreaterThanOrEqualTo(carriedPast - 1e-9);
        // Riding back along the same line is not a change of line.
        assertThat(engine.stationCarriedPast("E")).isCloseTo(carriedPast, within(1e-9));
        assertThat(engine.stationTransfers("E")).isZero();
        var metrics = engine.snapshot().metrics();
        assertThat(metrics.stopsSkipped()).isEqualTo(1);
        assertThat(metrics.passengersCarriedPast()).isCloseTo(carriedPast, within(1e-9));
    }

    @Test
    void eachSkippedStopIsLoggedWithThePassengersCarriedPast() {
        SimulationEngine engine = delayed(HeadwayStrategy.SKIP_STOP);
        double before = engine.simTime();
        int guard = 0;
        while (engine.skippedStops().isEmpty() && guard++ < 1800) {
            engine.step(1);
        }
        double carriedPast = engine.trainPassengersTo("L1-2", "D");
        assertThat(engine.skippedStops()).singleElement().satisfies(skip -> {
            assertThat(skip.trainId()).isEqualTo("L1-2");
            assertThat(skip.lineId()).isEqualTo("L1");
            assertThat(skip.stationId()).isEqualTo("D");
            assertThat(skip.simTime()).isBetween(before, engine.simTime());
            assertThat(skip.latenessSeconds()).isGreaterThanOrEqualTo(SimulationEngine.SKIP_STOP_LATENESS_SECONDS);
            assertThat(skip.passengersCarriedPast()).isPositive().isCloseTo(carriedPast, within(1e-9));
        });
        assertThat(engine.skippedStopsSince(before)).isEqualTo(engine.skippedStops());
        assertThat(engine.skippedStopsSince(engine.simTime() + 1)).isEmpty();
        // The log and the metric count the same skips.
        engine.run(1800, 1);
        assertThat(engine.skippedStops()).hasSize(engine.snapshot().metrics().stopsSkipped());
    }

    @Test
    void copiesKeepTheSkippedStopLogSoFarButNotLaterSkips() {
        SimulationEngine engine = delayed(HeadwayStrategy.SKIP_STOP);
        SimulationEngine before = engine.copy();
        engine.run(1800, 1);
        SimulationEngine after = engine.copy();
        assertThat(engine.skippedStops()).isNotEmpty();
        assertThat(before.skippedStops()).isEmpty();
        assertThat(after.skippedStops()).isEqualTo(engine.skippedStops());
    }

    @Test
    void aTrainNeverSkipsTwoStopsInARow() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLineWithQuietStop(), START, HeadwayStrategy.SKIP_STOP);
        engine.run(WARM_UP, 1);
        engine.injectDelay("L1-2", 240);
        int previousStop = -1;
        boolean previousSkipped = false;
        int seen = 0;
        for (int i = 0; i < 1800; i++) {
            engine.step(1);
            List<SimEvent> now = skips(engine);
            int stop = engine.train("L1-2").stop();
            if (stop != previousStop) {
                boolean skipped = now.size() > seen;
                assertThat(previousSkipped && skipped).as("consecutive skips at stop %d", stop).isFalse();
                previousSkipped = skipped;
                previousStop = stop;
                seen = now.size();
            }
        }
    }

    @Test
    void aLoneTrainNeverSkipsAStop() {
        // Nobody follows it, so passengers on a skipped platform would wait a whole loop.
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.SKIP_STOP);
        engine.injectDelay("L1-1", 600);
        engine.run(1800, 1);
        assertThat(skips(engine)).isEmpty();
    }

    @Test
    void interchangesAreNeverSkipped() {
        SimulationEngine engine = new SimulationEngine(TestNetworks.twoLines(), START, HeadwayStrategy.SKIP_STOP);
        engine.run(WARM_UP, 1);
        engine.injectDelay("L1-2", 240);
        engine.run(1800, 1);
        assertThat(skips(engine)).noneSatisfy(e -> assertThat(e.message()).contains("through C"));
    }

    @Test
    void skipStopIsDeterministic() {
        SimulationEngine a = delayed(HeadwayStrategy.SKIP_STOP);
        SimulationEngine b = a.copy();
        a.run(1800, 1);
        b.run(1800, 1);
        assertThat(a.snapshot().metrics()).isEqualTo(b.snapshot().metrics());
        assertThat(skips(a)).isEqualTo(skips(b));
    }
}
