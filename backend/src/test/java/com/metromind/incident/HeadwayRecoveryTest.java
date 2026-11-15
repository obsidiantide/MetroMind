package com.metromind.incident;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class HeadwayRecoveryTest {

    @Test
    void recoversAtTheFirstSampleAfterTheLastDisruptedOne() {
        HeadwayRecovery.Result r = HeadwayRecovery.measure(
                List.of(0.10, 0.10, 0.10, 0.10, 0.10, 0.10),
                List.of(0.10, 0.30, 0.20, 0.40, 0.12, 0.11), 30);

        assertThat(r.recovered()).isTrue();
        assertThat(r.recoverySeconds()).isEqualTo(120.0);
        assertThat(r.peakCvExcess()).isCloseTo(0.30, within(1e-9));
    }

    @Test
    void briefReturnToNormalDoesNotCountAsRecovery() {
        HeadwayRecovery.Result r = HeadwayRecovery.measure(
                List.of(0.1, 0.1, 0.1, 0.1, 0.1),
                List.of(0.3, 0.1, 0.3, 0.1, 0.1), 60);

        assertThat(r.recoverySeconds()).isEqualTo(180.0);
    }

    @Test
    void stillDisruptedAtTheHorizonHasNoRecoveryTime() {
        HeadwayRecovery.Result r = HeadwayRecovery.measure(
                List.of(0.1, 0.1, 0.1), List.of(0.1, 0.2, 0.25), 30);

        assertThat(r.recovered()).isFalse();
        assertThat(r.recoverySeconds()).isNull();
        assertThat(r.peakCvExcess()).isCloseTo(0.15, within(1e-9));
    }

    @Test
    void neverDisruptedRecoversImmediately() {
        HeadwayRecovery.Result r = HeadwayRecovery.measure(
                List.of(0.2, 0.3, 0.2), List.of(0.22, 0.31, 0.18), 30);

        assertThat(r.recoverySeconds()).isZero();
    }

    @Test
    void bunchingThatTheBaselineAlsoHasIsNotIncidentDamage() {
        HeadwayRecovery.Result r = HeadwayRecovery.measure(
                List.of(0.1, 0.4, 0.6), List.of(0.1, 0.42, 0.63), 30);

        assertThat(r.recoverySeconds()).isZero();
    }

    @Test
    void rejectsMismatchedSamplesAndNonPositiveInterval() {
        assertThatThrownBy(() -> HeadwayRecovery.measure(List.of(0.1), List.of(0.1, 0.2), 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HeadwayRecovery.measure(List.of(0.1), List.of(0.1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
