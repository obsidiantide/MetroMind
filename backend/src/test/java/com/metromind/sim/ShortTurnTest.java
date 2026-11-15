package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTurnTest {

    private static final double START = 8 * 3600;

    private static SimulationEngine delayed(HeadwayStrategy strategy) {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), START, strategy);
        engine.run(60, 1);
        engine.injectDelay("L1-2", 420);
        return engine;
    }

    private static List<SimEvent> shortTurns(SimulationEngine engine) {
        return engine.snapshot().recentEvents().stream()
                .filter(e -> e.type() == SimEvent.Type.TRAIN_SHORT_TURNED).toList();
    }

    @Test
    void onlyShortTurnStrategyReversesTrainsEarly() {
        assertThat(HeadwayStrategy.SHORT_TURN.shortTurnsLateTrains()).isTrue();
        for (HeadwayStrategy s : HeadwayStrategy.all()) {
            if (s != HeadwayStrategy.SHORT_TURN) {
                assertThat(s.shortTurnsLateTrains()).as(s.name()).isFalse();
                SimulationEngine engine = delayed(s);
                engine.run(1800, 1);
                assertThat(shortTurns(engine)).as(s.name()).isEmpty();
                assertThat(engine.shortTurns()).as(s.name()).isEmpty();
            }
        }
    }

    @Test
    void lateTrainWithAFollowerCloseBehindIsShortTurned() {
        SimulationEngine engine = delayed(HeadwayStrategy.SHORT_TURN);
        engine.run(1800, 1);
        assertThat(shortTurns(engine)).singleElement().satisfies(e -> {
            assertThat(e.subjectId()).isEqualTo("L1-2");
            assertThat(e.message()).contains("short-turned at D");
        });
        // Reversing hands the train a later slot in the opposite direction, so it is no longer reported late.
        assertThat(engine.train("L1-2").delaySeconds()).isLessThan(SimulationEngine.SHORT_TURN_LATENESS_SECONDS);
    }

    @Test
    void eachShortTurnIsLoggedWithItsStationAndLateness() {
        SimulationEngine engine = delayed(HeadwayStrategy.SHORT_TURN);
        double before = engine.simTime();
        engine.run(1800, 1);
        assertThat(engine.shortTurns()).singleElement().satisfies(turn -> {
            assertThat(turn.trainId()).isEqualTo("L1-2");
            assertThat(turn.lineId()).isEqualTo("L1");
            assertThat(turn.stationId()).isEqualTo("D");
            assertThat(turn.simTime()).isBetween(before, engine.simTime());
            assertThat(turn.latenessSeconds()).isGreaterThanOrEqualTo(SimulationEngine.SHORT_TURN_LATENESS_SECONDS);
        });
        assertThat(engine.shortTurnsSince(before)).isEqualTo(engine.shortTurns());
        assertThat(engine.shortTurnsSince(engine.simTime())).isEmpty();
    }

    @Test
    void copiesKeepTheShortTurnLogSoFarButNotLaterTurns() {
        SimulationEngine engine = delayed(HeadwayStrategy.SHORT_TURN);
        SimulationEngine before = engine.copy();
        engine.run(1800, 1);
        SimulationEngine after = engine.copy();
        assertThat(before.shortTurns()).isEmpty();
        assertThat(after.shortTurns()).isEqualTo(engine.shortTurns());
    }

    @Test
    void shortTurningRecoversLatenessFasterThanBalancing() {
        SimulationEngine shortTurn = delayed(HeadwayStrategy.SHORT_TURN);
        SimulationEngine balanced = delayed(HeadwayStrategy.BALANCED);
        shortTurn.run(1800, 1);
        balanced.run(1800, 1);

        var st = shortTurn.snapshot().metrics();
        var bal = balanced.snapshot().metrics();
        assertThat(st.maxDelaySeconds()).isLessThan(bal.maxDelaySeconds() * 0.75);
        assertThat(st.averageDelaySeconds()).isLessThan(bal.averageDelaySeconds() * 0.75);
    }

    @Test
    void aLoneTrainIsNeverShortTurned() {
        // Nobody follows it, so the stations it would abandon would get no service at all.
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleTrain(), START, HeadwayStrategy.SHORT_TURN);
        engine.injectDelay("L1-1", 600);
        engine.run(1800, 1);
        assertThat(shortTurns(engine)).isEmpty();
    }

    @Test
    void shortTurningIsDeterministic() {
        SimulationEngine a = delayed(HeadwayStrategy.SHORT_TURN);
        SimulationEngine b = a.copy();
        a.run(1800, 1);
        b.run(1800, 1);
        assertThat(a.snapshot().metrics()).isEqualTo(b.snapshot().metrics());
        assertThat(shortTurns(a)).isEqualTo(shortTurns(b));
    }
}
