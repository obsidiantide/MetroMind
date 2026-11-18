package com.metromind.kpi;

import com.metromind.sim.SimEvent;
import com.metromind.sim.SimulationSnapshot;
import com.metromind.sim.TrainPhase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class KpiAggregatorTest {

    /** 08:00 on the simulated clock. */
    private static final double T0 = 8 * 3600;

    private static SimulationSnapshot.TrainView train(String id, double delay, double load) {
        return new SimulationSnapshot.TrainView(id, "RED", "RED", "R1", "R2", 0.5, 0, 0, TrainPhase.RUNNING, delay, load,
                200, false);
    }

    private static SimulationSnapshot.Metrics metrics(double waiting, double cv) {
        return new SimulationSnapshot.Metrics(0, 0, waiting, 0, 0, 0, 0, 0, cv, 0, 0, 0, 0);
    }

    @Test
    void aggregatesAMinuteOncePastTheGracePeriod() {
        KpiAggregator agg = new KpiAggregator(30);
        assertThat(agg.onPosition(T0 + 5, train("RED-1", 10, 50))).isEmpty();
        assertThat(agg.onPosition(T0 + 5, train("RED-2", 30, 150))).isEmpty();
        assertThat(agg.onPosition(T0 + 35, train("RED-1", 20, 100))).isEmpty();
        assertThat(agg.onMetrics(T0 + 10, metrics(400, 0.2))).isEmpty();
        assertThat(agg.onMetrics(T0 + 40, metrics(600, 0.4))).isEmpty();
        assertThat(agg.onEvent(new SimEvent(T0 + 20, SimEvent.Type.DELAY_DETECTED, "RED-2", "late"))).isEmpty();
        assertThat(agg.onEvent(new SimEvent(T0 + 25, SimEvent.Type.INCIDENT, "RED-2", "breakdown"))).isEmpty();
        assertThat(agg.onEvent(new SimEvent(T0 + 26, SimEvent.Type.OVERLOAD_PREDICTED, "R3", "busy"))).isEmpty();

        // Still within 30 s of the minute's end: not closed yet.
        assertThat(agg.onPosition(T0 + 89, train("RED-1", 0, 0))).isEmpty();
        // Positions are past the grace period, but metrics still lag behind at T0 + 40.
        assertThat(agg.onPosition(T0 + 90, train("RED-1", 0, 0))).isEmpty();
        List<MinuteKpi> closed = agg.onMetrics(T0 + 90, metrics(0, 0));

        assertThat(closed).hasSize(1);
        MinuteKpi kpi = closed.get(0);
        assertThat(kpi.minute()).isEqualTo(480);
        assertThat(kpi.clock()).isEqualTo("08:00");
        assertThat(kpi.trainsReporting()).isEqualTo(2);
        assertThat(kpi.averageDelaySeconds()).isEqualTo(20.0);
        assertThat(kpi.maxDelaySeconds()).isEqualTo(30.0);
        assertThat(kpi.averageLoadFactor()).isCloseTo(0.5, within(1e-9));
        assertThat(kpi.averageWaiting()).isEqualTo(500.0);
        assertThat(kpi.maxWaiting()).isEqualTo(600.0);
        assertThat(kpi.averageHeadwayCv()).isCloseTo(0.3, within(1e-9));
        assertThat(kpi.delayAlerts()).isEqualTo(1);
        assertThat(kpi.incidents()).isEqualTo(1);
    }

    @Test
    void acceptsOutOfOrderSamplesWithinGraceAndDropsLaterOnes() {
        KpiAggregator agg = new KpiAggregator(30);
        agg.onPosition(T0 + 70, train("RED-1", 0, 0));
        // RED-2's partition lags behind: its sample for the first minute still counts.
        agg.onPosition(T0 + 50, train("RED-2", 60, 0));
        List<MinuteKpi> closed = agg.onPosition(T0 + 95, train("RED-1", 0, 0));
        assertThat(closed).singleElement().satisfies(k -> {
            assertThat(k.minute()).isEqualTo(480);
            assertThat(k.trainsReporting()).isEqualTo(1);
            assertThat(k.maxDelaySeconds()).isEqualTo(60.0);
        });

        // Minute 480 is closed: a sample for it is too late.
        assertThat(agg.onPosition(T0 + 55, train("RED-2", 99, 0))).isEmpty();
        assertThat(agg.lateSamples()).isEqualTo(1);
    }

    @Test
    void aBacklogOnPositionsHoldsMinutesOpenInsteadOfDroppingSamples() {
        KpiAggregator agg = new KpiAggregator(30);
        List<MinuteKpi> closed = new ArrayList<>();
        closed.addAll(agg.onPosition(T0 + 5, train("RED-1", 10, 0)));
        // The metrics listener races four minutes ahead while positions are still catching up.
        for (int t = 0; t <= 240; t += 5) {
            closed.addAll(agg.onMetrics(T0 + t, metrics(100, 0)));
        }
        assertThat(closed).isEmpty();
        assertThat(agg.openMinutes()).isEqualTo(5);

        // The positions backlog drains: its samples for the first minute still count.
        closed.addAll(agg.onPosition(T0 + 50, train("RED-2", 40, 0)));
        closed.addAll(agg.onPosition(T0 + 95, train("RED-1", 0, 0)));
        assertThat(closed).singleElement().satisfies(k -> {
            assertThat(k.minute()).isEqualTo(480);
            assertThat(k.trainsReporting()).isEqualTo(2);
            assertThat(k.averageDelaySeconds()).isEqualTo(25.0);
        });
        assertThat(agg.lateSamples()).isZero();
        assertThat(agg.watermarks()).containsEntry(KpiAggregator.Source.POSITIONS, T0 + 95)
                .containsEntry(KpiAggregator.Source.METRICS, T0 + 240)
                .doesNotContainKey(KpiAggregator.Source.EVENTS);
    }

    @Test
    void sparseEventsDoNotHoldMinutesOpen() {
        KpiAggregator agg = new KpiAggregator(0);
        agg.onEvent(new SimEvent(T0 + 1, SimEvent.Type.INCIDENT, "RED-1", "breakdown"));
        agg.onPosition(T0 + 2, train("RED-1", 0, 0));
        agg.onMetrics(T0 + 2, metrics(0, 0));
        agg.onPosition(T0 + 60, train("RED-1", 0, 0));
        assertThat(agg.onMetrics(T0 + 60, metrics(0, 0))).singleElement()
                .satisfies(k -> assertThat(k.incidents()).isEqualTo(1));
    }

    @Test
    void closesTheOldestMinutesWhenAGatingSourceStalls() {
        KpiAggregator agg = new KpiAggregator(0, 3);
        agg.onPosition(T0, train("RED-1", 0, 0));
        List<MinuteKpi> closed = new ArrayList<>();
        // Positions stop at T0; metrics keep coming for five more minutes.
        for (int m = 0; m <= 5; m++) {
            closed.addAll(agg.onMetrics(T0 + m * 60, metrics(0, 0)));
        }
        assertThat(closed).extracting(MinuteKpi::minute).containsExactly(480L, 481L, 482L);
        assertThat(agg.openMinutes()).isEqualTo(3);
    }

    @Test
    void closesSeveralMinutesInOrderAfterAGap() {
        KpiAggregator agg = new KpiAggregator(0);
        List<MinuteKpi> closed = new ArrayList<>();
        closed.addAll(agg.onPosition(T0, train("RED-1", 0, 0)));
        closed.addAll(agg.onPosition(T0 + 61, train("RED-1", 0, 0)));
        closed.addAll(agg.onPosition(T0 + 125, train("RED-1", 0, 0)));
        assertThat(closed).extracting(MinuteKpi::minute).containsExactly(480L, 481L);
        // A jump closes the open minute; minutes 483 and 484 saw no message, so nothing is emitted for them.
        assertThat(agg.onPosition(T0 + 300, train("RED-1", 0, 0))).extracting(MinuteKpi::minute)
                .containsExactly(482L);
        assertThat(agg.flush()).extracting(MinuteKpi::minute).containsExactly(485L);
        assertThat(agg.flush()).isEmpty();
    }

    @Test
    void minutesWithoutASourceReportZeros() {
        KpiAggregator agg = new KpiAggregator(0);
        agg.onEvent(new SimEvent(T0, SimEvent.Type.DELAY_DETECTED, "RED-1", "late"));
        MinuteKpi kpi = agg.flush().get(0);
        assertThat(kpi.trainsReporting()).isZero();
        assertThat(kpi.maxDelaySeconds()).isZero();
        assertThat(kpi.maxWaiting()).isZero();
        assertThat(kpi.delayAlerts()).isEqualTo(1);
    }

    @Test
    void clockWrapsPastMidnight() {
        assertThat(MinuteKpi.clockOf(24 * 60 + 5)).isEqualTo("00:05");
        assertThat(MinuteKpi.clockOf(23 * 60 + 59)).isEqualTo("23:59");
    }

    @Test
    void sameMessagesGiveTheSameKpis() {
        assertThat(replay()).isEqualTo(replay());
    }

    @Test
    void rejectsNegativeGrace() {
        assertThatThrownBy(() -> new KpiAggregator(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KpiAggregator(30, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<MinuteKpi> replay() {
        KpiAggregator agg = new KpiAggregator();
        List<MinuteKpi> out = new ArrayList<>();
        for (int t = 0; t < 600; t += 5) {
            out.addAll(agg.onPosition(T0 + t, train("RED-" + (t % 3), t % 70, t % 200)));
            out.addAll(agg.onMetrics(T0 + t, metrics(t, (t % 10) / 10.0)));
        }
        out.addAll(agg.flush());
        return out;
    }
}
