package com.metromind.sim;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DemandHoldingTest {

    private static final double MORNING_PEAK = 8.25 * 3600;
    private static final double NIGHT = 3 * 3600;

    @Test
    void targetKeepsTheEvenHeadwayOffPeakAndShrinksWithDemand() {
        assertThat(HeadwayStrategy.demandTargetFraction(0.15)).isEqualTo(1.0);
        assertThat(HeadwayStrategy.demandTargetFraction(HeadwayStrategy.DEMAND_REFERENCE_MULTIPLIER)).isEqualTo(1.0);
        assertThat(HeadwayStrategy.demandTargetFraction(1.6)).isCloseTo(Math.sqrt(0.5), within(1e-9));
        assertThat(HeadwayStrategy.demandTargetFraction(1.2))
                .isGreaterThan(HeadwayStrategy.demandTargetFraction(1.6));
        assertThat(HeadwayStrategy.demandTargetFraction(DemandProfile.multiplier(MORNING_PEAK)))
                .isEqualTo(HeadwayStrategy.DEMAND_MIN_TARGET_FRACTION);
    }

    @Test
    void holdsLessThanForwardHoldingWhenDemandIsHigh() {
        HeadwayStrategy demand = HeadwayStrategy.DEMAND_HOLDING;
        double even = 300;
        double gapAhead = 120;

        double offPeak = demand.holdSeconds(gapAhead, 400, demand.targetHeadway(even, 0.5));
        double peak = demand.holdSeconds(gapAhead, 400, demand.targetHeadway(even, 3.0));

        assertThat(offPeak).isEqualTo(HeadwayStrategy.FORWARD_HOLDING.holdSeconds(gapAhead, 400, even));
        assertThat(peak).isLessThan(offPeak);
        assertThat(peak).isEqualTo(Math.max(0, 0.8 * even * HeadwayStrategy.DEMAND_MIN_TARGET_FRACTION - gapAhead));
    }

    @Test
    void otherStrategiesKeepTheEvenHeadway() {
        for (HeadwayStrategy s : HeadwayStrategy.all()) {
            if (s != HeadwayStrategy.DEMAND_HOLDING) {
                assertThat(s.targetHeadway(300, 3.0)).as(s.name()).isEqualTo(300);
            }
        }
    }

    @Test
    void runsLikeForwardHoldingAtNight() {
        SimulationEngine forward = delayed(HeadwayStrategy.FORWARD_HOLDING, NIGHT);
        SimulationEngine demand = delayed(HeadwayStrategy.DEMAND_HOLDING, NIGHT);

        assertThat(demand.train("L1-2").arrivals()).isEqualTo(forward.train("L1-2").arrivals());
        assertThat(demand.passengerRideSeconds()).isEqualTo(forward.passengerRideSeconds());
    }

    @Test
    void peakHoldingKeepsPassengersOnBoardForLess() {
        SimulationEngine forward = delayed(HeadwayStrategy.FORWARD_HOLDING, MORNING_PEAK - 1800);
        SimulationEngine demand = delayed(HeadwayStrategy.DEMAND_HOLDING, MORNING_PEAK - 1800);

        assertThat(demand.passengerRideSeconds()).isLessThan(forward.passengerRideSeconds());
    }

    private static SimulationEngine delayed(HeadwayStrategy strategy, double start) {
        SimulationEngine engine = new SimulationEngine(TestNetworks.singleLine(), start, strategy);
        engine.setRecordArrivals(true);
        engine.run(300, 1);
        engine.injectDelay("L1-2", 180);
        engine.run(2400, 1);
        return engine;
    }
}
