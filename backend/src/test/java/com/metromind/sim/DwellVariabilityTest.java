package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class DwellVariabilityTest {

    @Test
    void noneKeepsPassengerDrivenDwells() {
        assertThat(DwellVariability.NONE.enabled()).isFalse();
        assertThat(DwellVariability.NONE.factor("L1-1", 7)).isEqualTo(1.0);
    }

    @Test
    void drawsAreAPureFunctionOfSeedTrainAndCall() {
        DwellVariability v = new DwellVariability(0.1, 42);
        assertThat(v.factor("L1-1", 3)).isEqualTo(new DwellVariability(0.1, 42).factor("L1-1", 3));
        assertThat(v.factor("L1-1", 3)).isNotEqualTo(v.factor("L1-1", 4));
        assertThat(v.factor("L1-1", 3)).isNotEqualTo(v.factor("L1-2", 3));
        assertThat(v.factor("L1-1", 3)).isNotEqualTo(new DwellVariability(0.1, 43).factor("L1-1", 3));
    }

    @Test
    void dwellDrawsAreIndependentOfRunTimeDrawsFromTheSameSeed() {
        DwellVariability dwell = new DwellVariability(0.1, 9);
        RunTimeVariability run = new RunTimeVariability(0.1, 9);
        long same = IntStream.range(0, 1000).filter(i -> dwell.factor("T-1", i) == run.factor("T-1", i)).count();
        assertThat(same).isZero();
    }

    @Test
    void factorsCentreOnOneWithTheGivenSpread() {
        DwellVariability v = new DwellVariability(0.2, 7);
        double[] logs = IntStream.range(0, 20_000).mapToDouble(i -> Math.log(v.factor("T-" + (i % 50), i / 50))).toArray();
        double mean = Arrays.stream(logs).average().orElseThrow();
        double sd = Math.sqrt(Arrays.stream(logs).map(x -> (x - mean) * (x - mean)).sum() / logs.length);

        assertThat(mean).isCloseTo(0, within(0.01));
        assertThat(sd).isCloseTo(0.2, within(0.01));
        assertThat(Arrays.stream(logs).map(Math::abs).max().orElseThrow()).isLessThanOrEqualTo(0.6 + 1e-12);
    }

    @Test
    void rejectsSpreadsOutsideTheSupportedRange() {
        assertThatThrownBy(() -> new DwellVariability(-0.01, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DwellVariability(0.3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DwellVariability(Double.NaN, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
