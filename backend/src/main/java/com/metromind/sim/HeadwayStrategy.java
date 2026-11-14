package com.metromind.sim;

/**
 * Decides how long a train should be held at a platform before departing, to keep headways regular.
 * Implementations must be stateless and deterministic so counterfactual runs are reproducible.
 */
public interface HeadwayStrategy {

    String name();

    /**
     * @param forwardHeadway  seconds (along the nominal loop) to the train ahead
     * @param backwardHeadway seconds to the train behind
     * @param targetHeadway   ideal even headway for the line
     * @return seconds to hold the train, 0 for none
     */
    double holdSeconds(double forwardHeadway, double backwardHeadway, double targetHeadway);

    /**
     * Whether the engine may bring spare trains out of a line's depot under this strategy when it predicts
     * overload, and send extra trains back when demand eases.
     */
    default boolean dispatchesFromDepot() {
        return false;
    }

    /** No control: trains depart as soon as dwelling is done. Bunching grows freely. */
    HeadwayStrategy NO_CONTROL = new HeadwayStrategy() {
        public String name() { return "NO_CONTROL"; }
        public double holdSeconds(double f, double b, double target) { return 0; }
    };

    /** Classic schedule-free holding: hold until the gap to the leader reaches the target headway. */
    HeadwayStrategy FORWARD_HOLDING = new HeadwayStrategy() {
        public String name() { return "FORWARD_HOLDING"; }
        public double holdSeconds(double f, double b, double target) {
            return clamp(0.8 * target - f);
        }
    };

    /** Two-way balancing: split the difference between forward and backward headways. */
    HeadwayStrategy BALANCED = new HeadwayStrategy() {
        public String name() { return "BALANCED"; }
        public double holdSeconds(double f, double b, double target) {
            return clamp((b - f) / 2.0);
        }
    };

    /**
     * Two-way balancing plus adaptive dispatching: spare trains leave the depot when the line's overload forecast
     * is high, and the gaps they open are evened out by the balancing hold.
     */
    HeadwayStrategy ADAPTIVE_DISPATCH = new HeadwayStrategy() {
        public String name() { return "ADAPTIVE_DISPATCH"; }
        public double holdSeconds(double f, double b, double target) {
            return BALANCED.holdSeconds(f, b, target);
        }
        public boolean dispatchesFromDepot() { return true; }
    };

    double MAX_HOLD_SECONDS = 120;

    private static double clamp(double v) {
        return Math.max(0, Math.min(MAX_HOLD_SECONDS, v));
    }

    static HeadwayStrategy byName(String name) {
        return switch (name.toUpperCase()) {
            case "NO_CONTROL" -> NO_CONTROL;
            case "FORWARD_HOLDING" -> FORWARD_HOLDING;
            case "BALANCED" -> BALANCED;
            case "ADAPTIVE_DISPATCH" -> ADAPTIVE_DISPATCH;
            default -> throw new IllegalArgumentException("Unknown strategy " + name);
        };
    }

    static java.util.List<HeadwayStrategy> all() {
        return java.util.List.of(NO_CONTROL, FORWARD_HOLDING, BALANCED, ADAPTIVE_DISPATCH);
    }
}
