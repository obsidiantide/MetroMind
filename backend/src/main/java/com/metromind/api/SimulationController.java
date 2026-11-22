package com.metromind.api;

import com.metromind.incident.AnalysisRecord;
import com.metromind.incident.BenchmarkJobs;
import com.metromind.incident.BenchmarkReport;
import com.metromind.incident.DelayImpactReport;
import com.metromind.incident.IncidentImpactReport;
import com.metromind.incident.IncidentRecord;
import com.metromind.incident.TimeOfDayReport;
import com.metromind.network.MetroNetwork;
import com.metromind.sim.ClosureResponse;
import com.metromind.sim.Departure;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.ReliefOverrun;
import com.metromind.sim.SimulationService;
import com.metromind.sim.SimulationSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class SimulationController {

    private final MetroNetwork network;
    private final SimulationService simulation;

    public SimulationController(MetroNetwork network, SimulationService simulation) {
        this.network = network;
        this.simulation = simulation;
    }

    @Tag(name = "Network")
    @Operation(summary = "Stations and lines of the simulated network")
    @GetMapping("/network")
    public MetroNetwork network() {
        return network;
    }

    @Tag(name = "Simulation")
    @Operation(summary = "Latest snapshot of the live simulation")
    @GetMapping("/state")
    public SimulationSnapshot state() {
        return simulation.snapshot();
    }

    /** Live departure board: trains leaving the station in the next {@code horizonSeconds}, soonest first. */
    @Tag(name = "Network")
    @Operation(summary = "Departure board of a station: planned vs expected departures")
    @GetMapping("/stations/{stationId}/departures")
    public List<Departure> departures(@PathVariable String stationId,
                                      @RequestParam(defaultValue = "1800") @Min(60) @Max(7200) double horizonSeconds) {
        return simulation.departures(stationId, horizonSeconds);
    }

    @Tag(name = "Simulation")
    @Operation(summary = "Names of the available headway strategies")
    @GetMapping("/strategies")
    public List<String> strategies() {
        return HeadwayStrategy.all().stream().map(HeadwayStrategy::name).toList();
    }

    @Tag(name = "Simulation")
    @Operation(summary = "Switch the live headway strategy")
    @ApiResponse(responseCode = "204", description = "Strategy switched")
    @PutMapping("/strategy")
    public ResponseEntity<Void> setStrategy(@RequestBody Map<String, String> body) {
        simulation.setStrategy(body.getOrDefault("strategy", "NO_CONTROL"));
        return ResponseEntity.noContent().build();
    }

    @Tag(name = "Simulation")
    @Operation(summary = "Pause or resume the simulation clock")
    @ApiResponse(responseCode = "204", description = "Clock paused or resumed")
    @PostMapping("/simulation/{action:pause|resume}")
    public ResponseEntity<Void> pause(@PathVariable String action) {
        simulation.setPaused("pause".equals(action));
        return ResponseEntity.noContent().build();
    }

    /** Incidents injected into the live simulation, most recent first. */
    @Tag(name = "Incidents")
    @Operation(summary = "Incident history, newest first")
    @GetMapping("/incidents")
    public List<IncidentRecord> incidents(@RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return simulation.incidentHistory(limit);
    }

    /** Delay-impact analyses run so far, with their full reports, most recent first. */
    @Tag(name = "Analysis")
    @Operation(summary = "Past delay-impact analyses with their reports, newest first")
    @GetMapping("/incidents/analyses")
    public List<AnalysisRecord> analyses(@RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return simulation.analysisHistory(limit);
    }

    public record IncidentRequest(@NotBlank String trainId, @Min(1) @Max(3600) double seconds) {
    }

    @Tag(name = "Incidents")
    @Operation(summary = "Delay a train in the live simulation")
    @ApiResponse(responseCode = "202", description = "Delay injected")
    @PostMapping("/incidents/delay")
    public ResponseEntity<Void> delay(@Valid @RequestBody IncidentRequest req) {
        simulation.injectDelay(req.trainId(), req.seconds());
        return ResponseEntity.accepted().build();
    }

    @Tag(name = "Incidents")
    @Operation(summary = "Break a train down until it is repaired")
    @ApiResponse(responseCode = "202", description = "Breakdown injected")
    @PostMapping("/incidents/breakdown")
    public ResponseEntity<Void> breakdown(@Valid @RequestBody IncidentRequest req) {
        simulation.injectBreakdown(req.trainId(), req.seconds());
        return ResponseEntity.accepted().build();
    }

    /**
     * Door fault: the train stays at its platform for {@code seconds} of extra dwell, or at the next platform it
     * calls at if it is running. Unlike a delay it never stops a train between stations.
     */
    @Tag(name = "Incidents")
    @Operation(summary = "Hold a train at its platform with a door fault")
    @ApiResponse(responseCode = "202", description = "Door fault injected")
    @PostMapping("/incidents/door-fault")
    public ResponseEntity<Void> doorFault(@Valid @RequestBody IncidentRequest req) {
        simulation.injectDoorFault(req.trainId(), req.seconds());
        return ResponseEntity.accepted().build();
    }

    /** @param response what trains do at the closure; defaults to {@link ClosureResponse#WAIT} */
    public record SectionClosureRequest(@NotBlank String lineId, @NotBlank String fromStationId,
                                        @NotBlank String toStationId, @Min(1) @Max(7200) double seconds,
                                        ClosureResponse response) {
    }

    /** Signal or track failure: closes the track between two adjacent stations of a line, both directions. */
    @Tag(name = "Incidents")
    @Operation(summary = "Close a track section between two adjacent stations")
    @ApiResponse(responseCode = "202", description = "Section closed")
    @PostMapping("/incidents/section-closure")
    public ResponseEntity<Void> closeSection(@Valid @RequestBody SectionClosureRequest req) {
        simulation.closeSection(req.lineId(), req.fromStationId(), req.toStationId(), req.seconds(),
                req.response() == null ? ClosureResponse.WAIT : req.response());
        return ResponseEntity.accepted().build();
    }

    public record StationClosureRequest(@NotBlank String stationId, @Min(1) @Max(7200) double seconds) {
    }

    /** Closes a station: trains run through it and its passengers use the neighbouring stations. */
    @Tag(name = "Incidents")
    @Operation(summary = "Close a station; trains run through it")
    @ApiResponse(responseCode = "202", description = "Station closed")
    @PostMapping("/incidents/station-closure")
    public ResponseEntity<Void> closeStation(@Valid @RequestBody StationClosureRequest req) {
        simulation.closeStation(req.stationId(), req.seconds());
        return ResponseEntity.accepted().build();
    }

    /**
     * A traction power failure: the whole line, or with {@code fromStationId} and {@code toStationId} only the
     * feeder section between those two stations of the line.
     */
    public record PowerFailureRequest(@NotBlank String lineId, @Min(1) @Max(7200) double seconds,
                                      String fromStationId, String toStationId) {

        /** Whether only a feeder section loses power; giving just one end of it is a bad request. */
        boolean sectional() {
            if ((fromStationId == null) != (toStationId == null)) {
                throw new IllegalArgumentException("A sectional power failure needs both fromStationId and toStationId");
            }
            return fromStationId != null;
        }

        BenchmarkReport.Scenario scenario() {
            return sectional()
                    ? BenchmarkReport.Scenario.sectionalPowerFailure(lineId, fromStationId, toStationId, seconds)
                    : BenchmarkReport.Scenario.powerFailure(lineId, seconds);
        }
    }

    /**
     * Traction power failure: every train of the line, or with {@code fromStationId} and {@code toStationId} every
     * train on the feeder section between them, stops where it is until power is restored; the rest of the line runs
     * up to the dead stretch.
     */
    @Tag(name = "Incidents")
    @Operation(summary = "Cut traction power to a line or one feeder section; its trains stop where they are")
    @ApiResponse(responseCode = "202", description = "Power cut")
    @PostMapping("/incidents/power-failure")
    public ResponseEntity<Void> cutPower(@Valid @RequestBody PowerFailureRequest req) {
        if (req.sectional()) {
            simulation.cutPower(req.lineId(), req.fromStationId(), req.toStationId(), req.seconds());
        } else {
            simulation.cutPower(req.lineId(), req.seconds());
        }
        return ResponseEntity.accepted().build();
    }

    /** What-if: impact of delaying a train, compared across all headway strategies. Does not touch live state. */
    @Tag(name = "Analysis")
    @Operation(summary = "What-if: propagate a train delay under every strategy")
    @PostMapping("/analysis/delay-impact")
    public DelayImpactReport delayImpact(@Valid @RequestBody IncidentRequest req,
                                         @RequestParam(defaultValue = "1800") @Min(60) @Max(7200) double horizonSeconds) {
        return simulation.analyzeDelay(req.trainId(), req.seconds(), horizonSeconds);
    }

    /**
     * What-if: impact of closing a track section ({@code response} defaults to {@link ClosureResponse#WAIT}),
     * compared across all headway strategies. Does not touch live state.
     */
    @Tag(name = "Analysis")
    @Operation(summary = "What-if: impact of a track-section closure under every strategy")
    @PostMapping("/analysis/section-closure-impact")
    public IncidentImpactReport sectionClosureImpact(@Valid @RequestBody SectionClosureRequest req,
                                                     @RequestParam(defaultValue = "1800") @Min(60) @Max(7200)
                                                     double horizonSeconds) {
        return simulation.analyzeIncident(BenchmarkReport.Scenario.sectionClosure(req.lineId(), req.fromStationId(),
                req.toStationId(), req.response() == null ? ClosureResponse.WAIT : req.response(), req.seconds()),
                horizonSeconds);
    }

    /** What-if: impact of a door fault on a train, compared across all headway strategies. Does not touch live state. */
    @Tag(name = "Analysis")
    @Operation(summary = "What-if: impact of a door fault under every strategy")
    @PostMapping("/analysis/door-fault-impact")
    public IncidentImpactReport doorFaultImpact(@Valid @RequestBody IncidentRequest req,
                                                @RequestParam(defaultValue = "1800") @Min(60) @Max(7200)
                                                double horizonSeconds) {
        return simulation.analyzeIncident(BenchmarkReport.Scenario.train(BenchmarkReport.IncidentKind.DOOR_FAULT,
                req.trainId(), req.seconds()), horizonSeconds);
    }

    /** What-if: impact of closing a station, compared across all headway strategies. Does not touch live state. */
    @Tag(name = "Analysis")
    @Operation(summary = "What-if: impact of a station closure under every strategy")
    @PostMapping("/analysis/station-closure-impact")
    public IncidentImpactReport stationClosureImpact(@Valid @RequestBody StationClosureRequest req,
                                                     @RequestParam(defaultValue = "1800") @Min(60) @Max(7200)
                                                     double horizonSeconds) {
        return simulation.analyzeIncident(BenchmarkReport.Scenario.stationClosure(req.stationId(), req.seconds()),
                horizonSeconds);
    }

    /**
     * What-if: impact of a traction power failure on a line, or on one feeder section of it, compared across all
     * headway strategies. Does not touch live state.
     */
    @Tag(name = "Analysis")
    @Operation(summary = "What-if: impact of a traction power failure under every strategy")
    @PostMapping("/analysis/power-failure-impact")
    public IncidentImpactReport powerFailureImpact(@Valid @RequestBody PowerFailureRequest req,
                                                   @RequestParam(defaultValue = "1800") @Min(60) @Max(7200)
                                                   double horizonSeconds) {
        return simulation.analyzeIncident(req.scenario(), horizonSeconds);
    }

    /**
     * Strategy benchmark: runs every headway strategy against the same {@code scenarios} random incidents, drawn
     * from {@code seed}, and ranks them by mean and p95 passenger wait. {@code kinds} (comma-separated, e.g.
     * {@code DELAY,STATION_CLOSURE}) limits the incidents drawn; by default train delays, breakdowns, door faults,
     * track-section closures, station closures and traction power failures are all used. {@code metric} picks the ranking: {@code WAIT_PER_BOARDING}
     * (default) or {@code TRAVEL_TIME}. {@code runTimeVariability} (0 to 0.25) sets how much run times between
     * stations vary around nominal in every run, drawn from {@code seed}; by default as much as on the live engine.
     * {@code dwellVariability} (0 to 0.25) does the same for dwells at platforms. {@code reliefOverrunShare} (0 to 1)
     * and {@code reliefOverrunSeconds} (0 to 600) set how often crew changes overrun and by how long on average,
     * each defaulting to the live engine's; lines with their own late reliefs keep them. Does not touch live state.
     */
    @Tag(name = "Analysis")
    @Operation(summary = "Rank strategies over random incident scenarios")
    @PostMapping("/analysis/benchmark")
    public BenchmarkReport benchmark(@RequestParam(defaultValue = "10") @Min(1) @Max(50) int scenarios,
                                     @RequestParam(defaultValue = "1") long seed,
                                     @RequestParam(defaultValue = "1800") @Min(300) @Max(3600) double horizonSeconds,
                                     @RequestParam(required = false) Set<BenchmarkReport.IncidentKind> kinds,
                                     @RequestParam(defaultValue = "WAIT_PER_BOARDING")
                                     BenchmarkReport.RankingMetric metric,
                                     @RequestParam(required = false) @DecimalMin("0") @DecimalMax("0.25")
                                     Double runTimeVariability,
                                     @RequestParam(required = false) @DecimalMin("0") @DecimalMax("0.25")
                                     Double dwellVariability,
                                     @RequestParam(required = false) @DecimalMin("0") @DecimalMax("1")
                                     Double reliefOverrunShare,
                                     @RequestParam(required = false) @DecimalMin("0") @DecimalMax("600")
                                     Double reliefOverrunSeconds) {
        return simulation.benchmarkStrategies(scenarios, seed, horizonSeconds, drawn(kinds), metric,
                spread(runTimeVariability), dwellSpread(dwellVariability),
                reliefs(reliefOverrunShare, reliefOverrunSeconds));
    }

    /**
     * Strategy benchmark across times of day: the benchmark of {@code POST /analysis/benchmark} run from each of
     * {@code startTimes} ({@code HH:mm}, comma-separated, 00:30 or later), every one from a fresh copy of the
     * network warmed up for 30 simulated minutes to that time, then ranked over the whole day. Scenarios, run times,
     * dwells and late reliefs all come from {@code seed}; the spreads and late reliefs default to the live engine's.
     * Does not touch live state.
     */
    @Tag(name = "Analysis")
    @Operation(summary = "Rank strategies over random incidents at several times of day")
    @PostMapping("/analysis/benchmark/day")
    public TimeOfDayReport benchmarkDay(
            @RequestParam(defaultValue = "08:00,13:00,18:00,22:30") @Size(min = 1, max = 4) List<String> startTimes,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int scenarios,
            @RequestParam(defaultValue = "1") long seed,
            @RequestParam(defaultValue = "1800") @Min(300) @Max(3600) double horizonSeconds,
            @RequestParam(required = false) Set<BenchmarkReport.IncidentKind> kinds,
            @RequestParam(defaultValue = "WAIT_PER_BOARDING") BenchmarkReport.RankingMetric metric,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("0.25") Double runTimeVariability,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("0.25") Double dwellVariability,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("1") Double reliefOverrunShare,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("600") Double reliefOverrunSeconds) {
        return simulation.benchmarkTimesOfDay(startTimes, scenarios, seed, horizonSeconds, drawn(kinds), metric,
                spread(runTimeVariability), dwellSpread(dwellVariability),
                reliefs(reliefOverrunShare, reliefOverrunSeconds));
    }

    /**
     * The same benchmark as {@code POST /analysis/benchmark}, run as a background job: answers {@code 202 Accepted}
     * at once with the queued job and its URL in {@code Location}; poll that URL until the job has
     * {@code SUCCEEDED} (its {@code report} is then set) or {@code FAILED}. Answers {@code 429} while too many jobs
     * are already queued or running.
     */
    @Tag(name = "Analysis")
    @Operation(summary = "Queue a strategy benchmark as a background job")
    @ApiResponse(responseCode = "202", description = "Job queued; poll the Location header")
    @ApiResponse(responseCode = "429", description = "Too many benchmark jobs queued or running")
    @PostMapping("/analysis/benchmark/jobs")
    public ResponseEntity<BenchmarkJobs.Job<BenchmarkReport>> submitBenchmark(
            @RequestParam(defaultValue = "10") @Min(1) @Max(200) int scenarios,
            @RequestParam(defaultValue = "1") long seed,
            @RequestParam(defaultValue = "1800") @Min(300) @Max(7200) double horizonSeconds,
            @RequestParam(required = false) Set<BenchmarkReport.IncidentKind> kinds,
            @RequestParam(defaultValue = "WAIT_PER_BOARDING") BenchmarkReport.RankingMetric metric,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("0.25") Double runTimeVariability,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("0.25") Double dwellVariability,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("1") Double reliefOverrunShare,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("600") Double reliefOverrunSeconds) {
        BenchmarkJobs.Job<BenchmarkReport> job = simulation.submitBenchmark(scenarios, seed, horizonSeconds, drawn(kinds), metric,
                spread(runTimeVariability), dwellSpread(dwellVariability),
                reliefs(reliefOverrunShare, reliefOverrunSeconds));
        return ResponseEntity.accepted()
                .location(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(job.id())
                        .toUri())
                .body(job);
    }

    @Tag(name = "Analysis")
    @Operation(summary = "A benchmark job: status, progress and, once done, its report")
    @ApiResponse(responseCode = "200", description = "The job")
    @ApiResponse(responseCode = "404", description = "No such job, or it has been forgotten")
    @GetMapping("/analysis/benchmark/jobs/{id}")
    public ResponseEntity<BenchmarkJobs.Job<BenchmarkReport>> benchmarkJob(@PathVariable String id) {
        return ResponseEntity.of(simulation.benchmarkJob(id));
    }

    /**
     * Cancels a benchmark job. A queued job never starts; a running one stops after the simulated run in progress.
     * Answers with the cancelled job, or {@code 409} with the job unchanged if it had already succeeded or failed.
     */
    @Tag(name = "Analysis")
    @Operation(summary = "Cancel a queued or running benchmark job")
    @ApiResponse(responseCode = "200", description = "The job, now cancelled")
    @ApiResponse(responseCode = "404", description = "No such job, or it has been forgotten")
    @ApiResponse(responseCode = "409", description = "The job had already finished; it is returned unchanged")
    @DeleteMapping("/analysis/benchmark/jobs/{id}")
    public ResponseEntity<BenchmarkJobs.Job<BenchmarkReport>> cancelBenchmarkJob(@PathVariable String id) {
        return simulation.cancelBenchmarkJob(id)
                .map(job -> ResponseEntity.status(job.status() == BenchmarkJobs.Status.CANCELLED
                        ? HttpStatus.OK : HttpStatus.CONFLICT).body(job))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Tag(name = "Analysis")
    @Operation(summary = "Benchmark jobs still kept, newest first")
    @GetMapping("/analysis/benchmark/jobs")
    public List<BenchmarkJobs.Job<BenchmarkReport>> benchmarkJobs(@RequestParam(defaultValue = "20") @Min(1) @Max(20) int limit) {
        return simulation.benchmarkJobs(limit);
    }

    /**
     * The same benchmark as {@code POST /analysis/benchmark/day}, run as a background job like
     * {@code POST /analysis/benchmark/jobs}: answers {@code 202 Accepted} with the queued job and its URL in
     * {@code Location}, {@code 400} for bad start times and {@code 429} while too many jobs are queued or running.
     * Its progress counts the simulated runs of every period.
     */
    @Tag(name = "Analysis")
    @Operation(summary = "Queue a strategy benchmark across times of day as a background job")
    @ApiResponse(responseCode = "202", description = "Job queued; poll the Location header")
    @ApiResponse(responseCode = "429", description = "Too many benchmark jobs queued or running")
    @PostMapping("/analysis/benchmark/day/jobs")
    public ResponseEntity<BenchmarkJobs.Job<TimeOfDayReport>> submitDayBenchmark(
            @RequestParam(defaultValue = "08:00,13:00,18:00,22:30") @Size(min = 1, max = 4) List<String> startTimes,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int scenarios,
            @RequestParam(defaultValue = "1") long seed,
            @RequestParam(defaultValue = "1800") @Min(300) @Max(3600) double horizonSeconds,
            @RequestParam(required = false) Set<BenchmarkReport.IncidentKind> kinds,
            @RequestParam(defaultValue = "WAIT_PER_BOARDING") BenchmarkReport.RankingMetric metric,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("0.25") Double runTimeVariability,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("0.25") Double dwellVariability,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("1") Double reliefOverrunShare,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("600") Double reliefOverrunSeconds) {
        BenchmarkJobs.Job<TimeOfDayReport> job = simulation.submitDayBenchmark(startTimes, scenarios, seed,
                horizonSeconds, drawn(kinds), metric, spread(runTimeVariability), dwellSpread(dwellVariability),
                reliefs(reliefOverrunShare, reliefOverrunSeconds));
        return ResponseEntity.accepted()
                .location(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(job.id())
                        .toUri())
                .body(job);
    }

    @Tag(name = "Analysis")
    @Operation(summary = "A benchmark job across times of day: status, progress and, once done, its report")
    @ApiResponse(responseCode = "200", description = "The job")
    @ApiResponse(responseCode = "404", description = "No such job, or it has been forgotten")
    @GetMapping("/analysis/benchmark/day/jobs/{id}")
    public ResponseEntity<BenchmarkJobs.Job<TimeOfDayReport>> dayBenchmarkJob(@PathVariable String id) {
        return ResponseEntity.of(simulation.dayBenchmarkJob(id));
    }

    /** Cancels a benchmark job across times of day, answering like {@code DELETE /analysis/benchmark/jobs/{id}}. */
    @Tag(name = "Analysis")
    @Operation(summary = "Cancel a queued or running benchmark job across times of day")
    @ApiResponse(responseCode = "200", description = "The job, now cancelled")
    @ApiResponse(responseCode = "404", description = "No such job, or it has been forgotten")
    @ApiResponse(responseCode = "409", description = "The job had already finished; it is returned unchanged")
    @DeleteMapping("/analysis/benchmark/day/jobs/{id}")
    public ResponseEntity<BenchmarkJobs.Job<TimeOfDayReport>> cancelDayBenchmarkJob(@PathVariable String id) {
        return simulation.cancelDayBenchmarkJob(id)
                .map(job -> ResponseEntity.status(job.status() == BenchmarkJobs.Status.CANCELLED
                        ? HttpStatus.OK : HttpStatus.CONFLICT).body(job))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Tag(name = "Analysis")
    @Operation(summary = "Benchmark jobs across times of day still kept, newest first")
    @GetMapping("/analysis/benchmark/day/jobs")
    public List<BenchmarkJobs.Job<TimeOfDayReport>> dayBenchmarkJobs(
            @RequestParam(defaultValue = "20") @Min(1) @Max(20) int limit) {
        return simulation.dayBenchmarkJobs(limit);
    }

    /** The incident kinds to draw: all of them when none are named. */
    private static Set<BenchmarkReport.IncidentKind> drawn(Set<BenchmarkReport.IncidentKind> kinds) {
        return kinds == null || kinds.isEmpty()
                ? EnumSet.allOf(BenchmarkReport.IncidentKind.class) : EnumSet.copyOf(kinds);
    }

    /** The run-time spread a benchmark uses: the one requested, otherwise the live engine's. */
    private double spread(Double requested) {
        return requested != null ? requested : simulation.runTimeVariability().sigma();
    }

    /** The dwell spread a benchmark uses: the one requested, otherwise the live engine's. */
    private double dwellSpread(Double requested) {
        return requested != null ? requested : simulation.dwellVariability().sigma();
    }

    /**
     * The late reliefs a benchmark draws: the live engine's share and mean, each replaced by the one requested.
     */
    private ReliefOverrun reliefs(Double share, Double meanSeconds) {
        return simulation.reliefOverrun().overriddenBy(share, meanSeconds);
    }

    @ExceptionHandler(BenchmarkJobs.QueueFullException.class)
    public ResponseEntity<Map<String, String>> tooManyJobs(BenchmarkJobs.QueueFullException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
