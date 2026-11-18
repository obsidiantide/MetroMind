package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Short-turning on the trunk a branched line's patterns share ({@link TestNetworks#longTrunk()}: A–B–C). */
class TrunkShortTurnTest {

    private static final Set<String> TRUNK = Set.of("A", "B", "C");

    @Test
    void aLateBranchTrainHeadingForTheTerminalTurnsOnTheTrunkWhenAMainTrainIsCloseBehind() {
        // L-4's delay holds up L-7 (bound for Y) behind it. Its only fellow Y train is far away, but on its way back
        // to A a main-route train is close behind, and A and B are served by every train, so L-7 turns at B.
        SimulationEngine engine = new SimulationEngine(TestNetworks.longTrunk(), 8 * 3600, HeadwayStrategy.SHORT_TURN);
        engine.run(300, 1);
        engine.injectDelay("L-4", 480);
        engine.run(3600, 1);
        assertThat(engine.shortTurns()).anySatisfy(turn -> {
            assertThat(turn.trainId()).isEqualTo("L-7");
            assertThat(turn.stationId()).isEqualTo("B");
        });
    }

    @Test
    void trunkShortTurnsOnlyHappenOnTheWayToTheFirstTerminal() {
        // Turning back on the way out would abandon the far end of the train's own route, which no train of the
        // other pattern serves; so on the trunk a train only turns when it is heading for A.
        SimulationEngine live = new SimulationEngine(TestNetworks.longTrunk(), 8 * 3600, HeadwayStrategy.SHORT_TURN);
        live.run(300, 1);
        int trunkTurns = 0;
        for (TrainState victim : live.trains()) {
            SimulationEngine engine = live.copy();
            engine.injectDelay(victim.id(), 480);
            for (int i = 0; i < 3600; i++) {
                int before = engine.shortTurns().size();
                Map<String, Integer> direction = new HashMap<>();
                engine.trains().forEach(t -> direction.put(t.id(), engine.network().line("L").patterns().stream()
                        .filter(p -> p.id().equals(t.patternId())).findFirst().orElseThrow()
                        .directionAtStop(t.stop())));
                engine.step(1);
                for (ShortTurn turn : engine.shortTurns().subList(before, engine.shortTurns().size())) {
                    if (TRUNK.contains(turn.stationId())) {
                        trunkTurns++;
                        assertThat(direction.get(turn.trainId())).as("%s at %s", turn.trainId(), turn.stationId())
                                .isEqualTo(-1);
                    }
                }
            }
        }
        assertThat(trunkTurns).isPositive();
    }
}
