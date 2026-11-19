package com.metromind.incident;

import com.metromind.network.FleetPlanPeriod;
import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.TestNetworks;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TimeOfDayBenchmarkTest {

    private static final List<HeadwayStrategy> TWO = List.of(HeadwayStrategy.NO_CONTROL, HeadwayStrategy.BALANCED);
    private static final Set<BenchmarkReport.IncidentKind> DELAYS = EnumSet.of(BenchmarkReport.IncidentKind.DELAY);

    /** The test line with four trains at the peak and two from 10:00. */
    private static MetroNetwork planned() {
        MetroNetwork base = TestNetworks.singleLine();
        Line line = new Line("L1", "Line 1", "#f00", List.of("A", "B", "C", "D", "E", "F"),
                List.of(120, 120, 120, 120, 120), 4, 900, 0, List.of(), false, List.of(), List.of(),
                List.of(new FleetPlanPeriod("06:00", 4), new FleetPlanPeriod("10:00", 2)));
        return new MetroNetwork("Test", base.stations(), List.of(line));
    }

    private static TimeOfDayReport run(List<String> clocks, long seed) {
        return new TimeOfDayBenchmark().run(planned(), clocks, 2, seed, 600, TWO, DELAYS,
                BenchmarkReport.RankingMetric.WAIT_PER_BOARDING, 0.05, 0.1);
    }

    @Test
    void benchmarksEachTimeOfDayFromAWarmedUpNetwork() {
        TimeOfDayReport report = run(List.of("08:00", "13:00"), 3);

        assertThat(report.periods()).extracting(TimeOfDayReport.Period::startClock).containsExactly("08:00", "13:00");
        TimeOfDayReport.Period peak = report.periods().get(0);
        TimeOfDayReport.Period midday = report.periods().get(1);
        assertThat(peak.report().scenarios()).allSatisfy(s -> assertThat(s.trainId()).startsWith("L1-"));
        assertThat(peak.report().runTimeVariability()).isEqualTo(0.05);
        assertThat(midday.report().dwellVariability()).isEqualTo(0.1);
        // By midday the plan has cut the line to two trains, so passengers wait longer for one.
        double peakWait = peak.report().rankings().get(0).meanWaitSeconds();
        double middayWait = midday.report().rankings().get(0).meanWaitSeconds();
        assertThat(middayWait).isGreaterThan(peakWait);
    }

    @Test
    void dayRankingsAverageThePeriodsAndNameTheWorst() {
        TimeOfDayReport report = run(List.of("08:00", "13:00"), 3);

        assertThat(report.rankings()).extracting(TimeOfDayReport.DayRanking::rank).containsExactly(1, 2);
        assertThat(report.rankings()).extracting(TimeOfDayReport.DayRanking::strategy)
                .containsExactlyInAnyOrder("NO_CONTROL", "BALANCED");
        for (TimeOfDayReport.DayRanking day : report.rankings()) {
            double expected = report.periods().stream()
                    .mapToDouble(p -> p.report().rankings().stream().filter(r -> r.strategy().equals(day.strategy()))
                            .findFirst().orElseThrow().meanWaitSeconds())
                    .average().orElseThrow();
            assertThat(day.meanWaitSeconds()).isCloseTo(expected, within(1e-9));
            assertThat(day.worstStartClock()).isIn("08:00", "13:00");
            assertThat(day.worstRank()).isBetween(1, 2);
        }
        assertThat(report.rankings().get(0).meanWaitSeconds())
                .isLessThanOrEqualTo(report.rankings().get(1).meanWaitSeconds());
    }

    @Test
    void aSeedReproducesTheWholeReport() {
        assertThat(run(List.of("08:00", "21:30"), 5)).isEqualTo(run(List.of("08:00", "21:30"), 5));
    }

    @Test
    void rejectsStartTimesThatAreMalformedRepeatedOrTooSoonAfterMidnight() {
        assertThatThrownBy(() -> run(List.of(), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(List.of("8:00"), 1)).hasMessageContaining("HH:mm");
        assertThatThrownBy(() -> run(List.of("24:10"), 1)).hasMessageContaining("not a time of day");
        assertThatThrownBy(() -> run(List.of("00:20"), 1)).hasMessageContaining("warm-up");
        assertThatThrownBy(() -> run(List.of("08:00", "08:00"), 1)).hasMessageContaining("repeat");
    }
}
