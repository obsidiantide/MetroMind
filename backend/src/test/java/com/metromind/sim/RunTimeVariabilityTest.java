package com.metromind.sim;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RunTimeVariabilityTest {

    @Test
    void noneKeepsNominalRunTimes() {
        assertThat(RunTimeVariability.NONE.enabled()).isFalse();
        assertThat(RunTimeVariability.NONE.factor("L1-1", 7)).isEqualTo(1.0);
    }

    @Test
    void drawsAreAPureFunctionOfSeedTrainAndRun() {
        RunTimeVariability v = new RunTimeVariability(0.08, 42);
        assertThat(v.factor("L1-1", 3)).isEqualTo(new RunTimeVariability(0.08, 42).factor("L1-1", 3));
        assertThat(v.factor("L1-1", 3)).isNotEqualTo(v.factor("L1-1", 4));
        assertThat(v.factor("L1-1", 3)).isNotEqualTo(v.factor("L1-2", 3));
        assertThat(v.factor("L1-1", 3)).isNotEqualTo(new RunTimeVariability(0.08, 43).factor("L1-1", 3));
    }

    @Test
    void factorsCentreOnOneWithTheGivenSpread() {
        RunTimeVariability v = new RunTimeVariability(0.1, 7);
        double[] logs = IntStream.range(0, 20_000).mapToDouble(i -> Math.log(v.factor("T-" + (i % 50), i / 50))).toArray();
        double mean = java.util.Arrays.stream(logs).average().orElseThrow();
        double sd = Math.sqrt(java.util.Arrays.stream(logs).map(x -> (x - mean) * (x - mean)).sum() / logs.length);

        assertThat(mean).isCloseTo(0, within(0.005));
        assertThat(sd).isCloseTo(0.1, within(0.005));
        assertThat(java.util.Arrays.stream(logs).map(Math::abs).max().orElseThrow()).isLessThanOrEqualTo(0.3 + 1e-12);
    }

    @Test
    void rejectsSpreadsOutsideTheSupportedRange() {
        assertThatThrownBy(() -> new RunTimeVariability(-0.01, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunTimeVariability(0.3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunTimeVariability(Double.NaN, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
