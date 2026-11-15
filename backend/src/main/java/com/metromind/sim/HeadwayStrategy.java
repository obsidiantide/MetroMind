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
     * The headway this strategy aims for, passed to {@link #holdSeconds} as {@code targetHeadway}. The engine
     * supplies the line's even headway (loop time divided by trains in service) and the demand multiplier forecast
     * for the next few minutes; the default keeps the even headway whatever the demand.
     */
    default double targetHeadway(double evenHeadway, double forecastDemandMultiplier) {
        return evenHeadway;
    }

    /**
     * Whether the engine may bring spare trains out of a line's depot under this strategy when it predicts
     * overload, and send extra trains back when demand eases.
     */
    default boolean dispatchesFromDepot() {
        return false;
    }

    /**
     * Whether a train running well behind schedule may be short-turned: reversed at an intermediate platform so it
     * takes over a slot in the opposite direction, leaving the stations beyond to the train close behind it.
     */
    default boolean shortTurnsLateTrains() {
        return false;
    }

    /**
     * Whether a train running behind schedule may skip a quiet intermediate stop: it runs through without opening
     * its doors, leaving the few passengers waiting there to the train close behind it. Passengers on board for the
     * skipped station ride on to the next stop and come back on a train in the opposite direction.
     */
    default boolean skipsStopsForLateTrains() {
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

    /**
     * Two-way balancing plus short-turning for recovery: a train that has fallen far behind its timetable, with
     * a follower bunched up close behind, turns back early instead of carrying its lateness to the terminal.
     */
    HeadwayStrategy SHORT_TURN = new HeadwayStrategy() {
        public String name() { return "SHORT_TURN"; }
        public double holdSeconds(double f, double b, double target) {
            return BALANCED.holdSeconds(f, b, target);
        }
        public boolean shortTurnsLateTrains() { return true; }
    };

    /**
     * Two-way balancing plus skip-stop for recovery: a late train with a follower close behind runs through quiet
     * intermediate stops, saving their dwell, while the follower picks up the few passengers left waiting there.
     */
    HeadwayStrategy SKIP_STOP = new HeadwayStrategy() {
        public String name() { return "SKIP_STOP"; }
        public double holdSeconds(double f, double b, double target) {
            return BALANCED.holdSeconds(f, b, target);
        }
        public boolean skipsStopsForLateTrains() { return true; }
    };

    /**
     * Two-way balancing with both recovery tools: a moderately late train skips quiet stops, while a train late
     * enough to be short-turned keeps calling at every stop so it can reverse as soon as a follower closes up
     * behind it. Short turns take precedence because they recover a whole trip's lateness at once.
     */
    HeadwayStrategy SHORT_TURN_SKIP_STOP = new HeadwayStrategy() {
        public String name() { return "SHORT_TURN_SKIP_STOP"; }
        public double holdSeconds(double f, double b, double target) {
            return BALANCED.holdSeconds(f, b, target);
        }
        public boolean shortTurnsLateTrains() { return true; }
        public boolean skipsStopsForLateTrains() { return true; }
    };

    /**
     * Forward holding against a target that tracks forecast demand. Off-peak a train is held until the gap ahead
     * reaches the usual share of the even headway; as demand rises the target shrinks with the square root of the
     * demand multiplier (down to {@link #DEMAND_MIN_TARGET_FRACTION} of it), because in a peak every second of
     * holding delays a full train and lets platforms fill up, so regularity is traded for throughput.
     */
    HeadwayStrategy DEMAND_HOLDING = new HeadwayStrategy() {
        public String name() { return "DEMAND_HOLDING"; }
        public double targetHeadway(double evenHeadway, double forecastDemandMultiplier) {
            return evenHeadway * demandTargetFraction(forecastDemandMultiplier);
        }
        public double holdSeconds(double f, double b, double target) {
            return FORWARD_HOLDING.holdSeconds(f, b, target);
        }
    };

    double MAX_HOLD_SECONDS = 120;
    /** Demand multiplier at or below which {@link #DEMAND_HOLDING} keeps the full even headway as its target. */
    double DEMAND_REFERENCE_MULTIPLIER = 0.8;
    /** Smallest fraction of the even headway {@link #DEMAND_HOLDING} aims for, however high demand gets. */
    double DEMAND_MIN_TARGET_FRACTION = 0.6;

    /** Fraction of the even headway {@link #DEMAND_HOLDING} targets at the given demand multiplier. */
    static double demandTargetFraction(double demandMultiplier) {
        if (demandMultiplier <= DEMAND_REFERENCE_MULTIPLIER) {
            return 1.0;
        }
        return Math.max(DEMAND_MIN_TARGET_FRACTION, Math.sqrt(DEMAND_REFERENCE_MULTIPLIER / demandMultiplier));
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(MAX_HOLD_SECONDS, v));
    }

    static HeadwayStrategy byName(String name) {
        return switch (name.toUpperCase()) {
            case "NO_CONTROL" -> NO_CONTROL;
            case "FORWARD_HOLDING" -> FORWARD_HOLDING;
            case "BALANCED" -> BALANCED;
            case "ADAPTIVE_DISPATCH" -> ADAPTIVE_DISPATCH;
            case "SHORT_TURN" -> SHORT_TURN;
            case "DEMAND_HOLDING" -> DEMAND_HOLDING;
            case "SKIP_STOP" -> SKIP_STOP;
            case "SHORT_TURN_SKIP_STOP" -> SHORT_TURN_SKIP_STOP;
            default -> throw new IllegalArgumentException("Unknown strategy " + name);
        };
    }

    static java.util.List<HeadwayStrategy> all() {
        return java.util.List.of(NO_CONTROL, FORWARD_HOLDING, BALANCED, ADAPTIVE_DISPATCH, SHORT_TURN, DEMAND_HOLDING,
                SKIP_STOP, SHORT_TURN_SKIP_STOP);
    }
}
