package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ReliefOverrunTest {

    @Test
    void noneNeverOverruns() {
        assertThat(ReliefOverrun.NONE.enabled()).isFalse();
        assertThat(IntStream.range(0, 100).mapToDouble(i -> ReliefOverrun.NONE.extraSeconds("L1-1", i)).sum()).isZero();
        assertThat(new ReliefOverrun(0.5, 0, 1).enabled()).isFalse();
    }

    @Test
    void drawsAreAPureFunctionOfSeedTrainAndCall() {
        ReliefOverrun r = new ReliefOverrun(1, 60, 42);
        assertThat(r.extraSeconds("L1-1", 3)).isEqualTo(new ReliefOverrun(1, 60, 42).extraSeconds("L1-1", 3));
        assertThat(r.extraSeconds("L1-1", 3)).isNotEqualTo(r.extraSeconds("L1-1", 4));
        assertThat(r.extraSeconds("L1-1", 3)).isNotEqualTo(r.extraSeconds("L1-2", 3));
        assertThat(r.extraSeconds("L1-1", 3)).isNotEqualTo(new ReliefOverrun(1, 60, 43).extraSeconds("L1-1", 3));
    }

    @Test
    void theGivenShareOfReliefsOverrunsByTheGivenMeanOnAverage() {
        ReliefOverrun r = new ReliefOverrun(0.2, 90, 7);
        double[] extra = IntStream.range(0, 20_000).mapToDouble(i -> r.extraSeconds("T-" + (i % 50), i / 50)).toArray();
        long late = IntStream.range(0, extra.length).filter(i -> extra[i] > 0).count();
        double meanLate = IntStream.range(0, extra.length).filter(i -> extra[i] > 0)
                .mapToDouble(i -> extra[i]).average().orElseThrow();

        assertThat(late / (double) extra.length).isCloseTo(0.2, within(0.01));
        // The cap at four means trims the exponential's tail by e^-4 of its mean, under 2 %.
        assertThat(meanLate).isCloseTo(90 * (1 - Math.exp(-4)), within(4.0));
        assertThat(Arrays.stream(extra).min().orElseThrow()).isGreaterThanOrEqualTo(0);
        assertThat(Arrays.stream(extra).max().orElseThrow()).isLessThanOrEqualTo(4 * 90.0);
    }

    @Test
    void reliefDrawsAreIndependentOfDwellDrawsFromTheSameSeed() {
        ReliefOverrun relief = new ReliefOverrun(0.5, 60, 9);
        DwellVariability dwell = new DwellVariability(0.1, 9);
        // Late reliefs should not cluster on the calls with long dwell draws.
        double lateDwell = IntStream.range(0, 4000).filter(i -> relief.extraSeconds("T-1", i) > 0)
                .mapToDouble(i -> Math.log(dwell.factor("T-1", i))).average().orElseThrow();
        assertThat(lateDwell).isCloseTo(0, within(0.01));
    }

    @Test
    void rejectsSharesAndMeansOutsideTheSupportedRange() {
        assertThatThrownBy(() -> new ReliefOverrun(-0.1, 60, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReliefOverrun(1.1, 60, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReliefOverrun(0.1, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReliefOverrun(0.1, 601, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReliefOverrun(Double.NaN, 60, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLineOverridesOnlyWhatItDeclaresAndKeepsTheSeed() {
        ReliefOverrun engineWide = new ReliefOverrun(0.2, 90, 7);
        assertThat(engineWide.overriddenBy(null, null)).isSameAs(engineWide);
        assertThat(engineWide.overriddenBy(0.5, null)).isEqualTo(new ReliefOverrun(0.5, 90, 7));
        assertThat(engineWide.overriddenBy(null, 30.0)).isEqualTo(new ReliefOverrun(0.2, 30, 7));
    }
}
