package com.metromind.network;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A metro line. On an ordinary line trains run back and forth between the two terminals; on a ring line they run
 * round the circle in station order without reversing (see {@link LoopTopology} for how both become a loop of
 * stops). A ring served in both directions is two ring lines over the same stations, one listing them in reverse.
 *
 * @param stationIds     ordered stations from terminal A to terminal B
 * @param runSeconds     nominal running time between consecutive stations (size = stations - 1); a ring also
 *                       gives the run from its last station back to the first (size = stations)
 * @param trainCount     trains in service on this line, shared between its main route and branches
 * @param trainCapacity  passengers per train
 * @param spareTrains    extra trains kept in a depot next to the first terminal; strategies that dispatch from the
 *                       depot may bring them into service (0 if the network file leaves it out)
 * @param path           track alignment as {@code [lon, lat]} pairs from terminal A to terminal B, for drawing and
 *                       spatial queries; empty when only the stations are known (the track is then the straight
 *                       segments between them); a ring's path runs from its first station round to the first again
 * @param ring           whether trains circulate round the stations instead of reversing at terminals
 * @param timingStationIds stations where trains keep to the timetable, waiting there when early; empty for the
 *                       default: the terminals, or a ring's first station (a ring has no terminal to lay over at);
 *                       stations on a branch may be timing points too
 * @param branches       branches some of the line's trains run to instead of the end of the main route (empty for
 *                       an unbranched line; a ring cannot branch)
 * @param fleetPlan      time-of-day fleet plan, periods in order of their start; empty when the line runs
 *                       {@code trainCount} trains all day. {@code trainCount} is still the fleet placed on the line
 *                       when a run starts; trains beyond it come out of the depot, which holds enough for the
 *                       busiest period on top of the {@code spareTrains}
 */
public record Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                   int trainCount, int trainCapacity, int spareTrains, List<List<Double>> path, boolean ring,
                   List<String> timingStationIds, List<Branch> branches, List<FleetPlanPeriod> fleetPlan)
        implements LoopTopology {

    /** Line running the same fleet all day. */
    public Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                int trainCount, int trainCapacity, int spareTrains, List<List<Double>> path, boolean ring,
                List<String> timingStationIds, List<Branch> branches) {
        this(id, name, color, stationIds, runSeconds, trainCount, trainCapacity, spareTrains, path, ring,
                timingStationIds, branches, List.of());
    }

    /** Unbranched line. */
    public Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                int trainCount, int trainCapacity, int spareTrains, List<List<Double>> path, boolean ring,
                List<String> timingStationIds) {
        this(id, name, color, stationIds, runSeconds, trainCount, trainCapacity, spareTrains, path, ring,
                timingStationIds, List.of());
    }

    /** Line with the default timing points. */
    public Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                int trainCount, int trainCapacity, int spareTrains, List<List<Double>> path, boolean ring) {
        this(id, name, color, stationIds, runSeconds, trainCount, trainCapacity, spareTrains, path, ring, List.of());
    }

    /** Ordinary (non-ring) line with a track alignment. */
    public Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                int trainCount, int trainCapacity, int spareTrains, List<List<Double>> path) {
        this(id, name, color, stationIds, runSeconds, trainCount, trainCapacity, spareTrains, path, false);
    }

    /** Line whose track is the straight segments between its stations. */
    public Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                int trainCount, int trainCapacity, int spareTrains) {
        this(id, name, color, stationIds, runSeconds, trainCount, trainCapacity, spareTrains, List.of());
    }

    /** Line with no spare trains in its depot. */
    public Line(String id, String name, String color, List<String> stationIds, List<Integer> runSeconds,
                int trainCount, int trainCapacity) {
        this(id, name, color, stationIds, runSeconds, trainCount, trainCapacity, 0);
    }

    public Line {
        if (spareTrains < 0) {
            throw new IllegalArgumentException("Line " + id + ": spareTrains must not be negative");
        }
        if (stationIds.size() < 2) {
            throw new IllegalArgumentException("Line " + id + " needs at least two stations");
        }
        if (ring && stationIds.size() < 3) {
            throw new IllegalArgumentException("Ring line " + id + " needs at least three stations");
        }
        if (!ring && runSeconds.size() != stationIds.size() - 1) {
            throw new IllegalArgumentException("Line " + id + ": runSeconds must have stations-1 entries");
        }
        if (ring && runSeconds.size() != stationIds.size()) {
            throw new IllegalArgumentException("Ring line " + id
                    + ": runSeconds must have one entry per station (the last closes the ring)");
        }
        if (stationIds.stream().distinct().count() != stationIds.size()) {
            throw new IllegalArgumentException("Line " + id + " lists a station twice");
        }
        stationIds = List.copyOf(stationIds);
        runSeconds = List.copyOf(runSeconds);
        if (path == null) {
            path = List.of();
        }
        if (path.size() == 1) {
            throw new IllegalArgumentException("Line " + id + ": path needs at least two points");
        }
        for (List<Double> point : path) {
            if (point.size() != 2) {
                throw new IllegalArgumentException("Line " + id + ": path points must be [lon, lat]");
            }
        }
        path = path.stream().map(List::copyOf).toList();
        branches = branches == null ? List.of() : List.copyOf(branches);
        validateBranches(id, stationIds, ring, trainCount, branches);
        timingStationIds = timingStationIds == null ? List.of() : List.copyOf(timingStationIds);
        for (String sid : timingStationIds) {
            if (!stationIds.contains(sid) && branches.stream().noneMatch(b -> b.stationIds().contains(sid))) {
                throw new IllegalArgumentException("Line " + id + ": timing point " + sid + " is not on the line");
            }
        }
        fleetPlan = fleetPlan == null ? List.of() : List.copyOf(fleetPlan);
        for (int i = 0; i < fleetPlan.size(); i++) {
            FleetPlanPeriod period = fleetPlan.get(i);
            if (i > 0 && period.fromSeconds() <= fleetPlan.get(i - 1).fromSeconds()) {
                throw new IllegalArgumentException("Line " + id + ": fleet plan periods must be in order of their start");
            }
            if (period.trains() < branches.size() + 1) {
                throw new IllegalArgumentException("Line " + id + ": fleet plan period from " + period.from()
                        + " needs at least one train per service pattern (" + (branches.size() + 1) + ")");
            }
        }
    }

    /**
     * Trains the line should run at {@code simSeconds} (seconds since midnight of the first day): the fleet plan's
     * period in force then, or {@code trainCount} when the line has no plan.
     */
    public int plannedTrains(double simSeconds) {
        if (fleetPlan.isEmpty()) {
            return trainCount;
        }
        double timeOfDay = ((simSeconds % 86_400) + 86_400) % 86_400;
        FleetPlanPeriod current = fleetPlan.get(fleetPlan.size() - 1); // in force past midnight
        for (FleetPlanPeriod period : fleetPlan) {
            if (period.fromSeconds() <= timeOfDay) {
                current = period;
            }
        }
        return current.trains();
    }

    /** Most trains the line ever runs: its starting fleet or the busiest period of its fleet plan. */
    public int peakPlannedTrains() {
        return Math.max(trainCount, fleetPlan.stream().mapToInt(FleetPlanPeriod::trains).max().orElse(0));
    }

    /** Whether the network file gave the real track alignment rather than just the stations. */
    public boolean hasPath() {
        return !path.isEmpty();
    }

    private static void validateBranches(String id, List<String> stationIds, boolean ring, int trainCount,
                                         List<Branch> branches) {
        if (branches.isEmpty()) {
            return;
        }
        if (ring) {
            throw new IllegalArgumentException("Ring line " + id + " cannot have branches");
        }
        java.util.Set<String> seen = new java.util.HashSet<>(stationIds);
        int branchTrains = 0;
        for (Branch b : branches) {
            int j = stationIds.indexOf(b.junctionStationId());
            if (j <= 0 || j >= stationIds.size() - 1) {
                throw new IllegalArgumentException("Line " + id + ": branch junction " + b.junctionStationId()
                        + " must be an intermediate station of the main route");
            }
            for (String sid : b.stationIds()) {
                if (!seen.add(sid)) {
                    throw new IllegalArgumentException("Line " + id + ": branch station " + sid
                            + " is already on the line");
                }
            }
            int trains = branchTrainCount(trainCount, b);
            if (trains < 1) {
                throw new IllegalArgumentException("Line " + id + ": branch to " + b.terminalStationId()
                        + " gets no train out of " + trainCount);
            }
            branchTrains += trains;
        }
        if (branchTrains >= trainCount) {
            throw new IllegalArgumentException("Line " + id + ": branches leave no train for the main route");
        }
    }

    private static int branchTrainCount(int trainCount, Branch branch) {
        return (int) Math.round(trainCount * branch.share());
    }

    /** Every station the line serves: the main route, then each branch's stations. */
    public List<String> allStationIds() {
        if (branches.isEmpty()) {
            return stationIds;
        }
        List<String> all = new java.util.ArrayList<>(stationIds);
        branches.forEach(b -> all.addAll(b.stationIds()));
        return List.copyOf(all);
    }

    /**
     * The station sequences this line's trains follow. The main pattern (id = the line id) runs the line's own
     * stations; each branch adds a pattern (id = {@code line/branch-terminal}) that follows the main route from the
     * first terminal to the junction and then the branch. Each branch gets its {@link Branch#share()} of the
     * trains, rounded, and the main pattern the rest. A pattern keeps the line's timing points that lie on it.
     * Serialised with the line (read-only: a network file never gives it, it follows from the branches).
     */
    @JsonProperty(value = "patterns", access = JsonProperty.Access.READ_ONLY)
    public List<ServicePattern> patterns() {
        if (branches.isEmpty()) {
            return List.of(new ServicePattern(id, id, stationIds, runSeconds, ring, timingStationIds, trainCount));
        }
        List<ServicePattern> patterns = new java.util.ArrayList<>();
        int mainTrains = trainCount;
        for (Branch b : branches) {
            mainTrains -= branchTrainCount(trainCount, b);
        }
        patterns.add(new ServicePattern(id, id, stationIds, runSeconds, false, timingOn(stationIds), mainTrains));
        for (Branch b : branches) {
            int j = stationIds.indexOf(b.junctionStationId());
            List<String> stops = new java.util.ArrayList<>(stationIds.subList(0, j + 1));
            stops.addAll(b.stationIds());
            List<Integer> runs = new java.util.ArrayList<>(runSeconds.subList(0, j));
            runs.addAll(b.runSeconds());
            patterns.add(new ServicePattern(id + "/" + b.terminalStationId(), id, stops, runs, false,
                    timingOn(stops), branchTrainCount(trainCount, b)));
        }
        return List.copyOf(patterns);
    }

    private List<String> timingOn(List<String> stops) {
        return timingStationIds.stream().filter(stops::contains).toList();
    }
}
