package com.metromind.sim;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BranchRoutingTest {

    private static final double DWELL = SimulationEngine.NOMINAL_DWELL;
    /** Main pattern A–B–C–D: run 2 × 330 s plus six dwells, four trains. */
    private static final double MAIN_FREQUENCY = 4 / (660 + 6 * DWELL);
    /** Branch pattern A–B–X–Y: run 2 × 270 s plus six dwells, two trains. */
    private static final double BRANCH_FREQUENCY = 2 / (540 + 6 * DWELL);

    private final RoutePlanner routes = new RoutePlanner(TestNetworks.branched(), DWELL);
    private final int main = routes.patternIndex("L");
    private final int branch = routes.patternIndex("L/Y");

    private int st(String id) {
        return routes.index(id);
    }

    @Test
    void trunkPassengersBoardTheFirstTrainOfEitherPattern() {
        assertThat(routes.boards(main, 0)[st("B")]).isTrue();
        assertThat(routes.boards(branch, 0)[st("B")]).isTrue();
        // They wait half the combined headway of both patterns.
        double wait = 1 / (2 * (MAIN_FREQUENCY + BRANCH_FREQUENCY));
        assertThat(routes.travelSeconds(st("A"), st("B"))).isCloseTo(wait + 100 + DWELL, within(1e-6));
    }

    @Test
    void passengersBeyondTheJunctionOnlyBoardTrainsGoingThere() {
        assertThat(routes.boards(main, 0)[st("Y")]).isFalse();
        assertThat(routes.boards(branch, 0)[st("Y")]).isTrue();
        assertThat(routes.boards(main, 0)[st("D")]).isTrue();
        assertThat(routes.boards(branch, 0)[st("D")]).isFalse();

        double branchWait = 1 / (2 * BRANCH_FREQUENCY);
        assertThat(routes.travelSeconds(st("A"), st("Y")))
                .isCloseTo(branchWait + 100 + 90 + 80 + 3 * DWELL, within(1e-6));
        assertThat(routes.linesRidden(st("A"), st("Y"))).containsExactly("L");
    }

    @Test
    void aBranchTrainKeepsItsPassengersForTheBranchAtTheJunction() {
        // Branch pattern loop: A(0) B(1) X(2) Y(3) X(4) B(5); stop 1 is the junction, outbound.
        assertThat(routes.staysAboard(branch, 1)[st("Y")]).isTrue();
        assertThat(routes.staysAboard(branch, 1)[st("B")]).isFalse();
    }

    @Test
    void changingFromTheBranchToTheMainRouteHappensAtTheJunction() {
        assertThat(routes.linesRidden(st("Y"), st("D"))).containsExactly("L", "L");
        // Arriving inbound at B (branch stop 5), a passenger for D crosses to the outbound platform.
        assertThat(routes.staysAboard(branch, 5)[st("D")]).isFalse();
        StationState.QueueKey next = routes.transferQueue(branch, 5, st("D"));
        assertThat(next).isEqualTo(new StationState.QueueKey("L", 1));

        double expected = 1 / (2 * BRANCH_FREQUENCY) + 80 + 90 + 2 * DWELL
                + RoutePlanner.TRANSFER_WALK_SECONDS + 1 / (2 * MAIN_FREQUENCY) + 110 + 120 + 2 * DWELL;
        assertThat(routes.travelSeconds(st("Y"), st("D"))).isCloseTo(expected, within(1e-6));
    }
}
