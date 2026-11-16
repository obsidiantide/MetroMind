package com.metromind.api;

import com.metromind.incident.AnalysisRecord;
import com.metromind.incident.BenchmarkReport;
import com.metromind.incident.DelayImpactReport;
import com.metromind.incident.IncidentImpactReport;
import com.metromind.incident.IncidentRecord;
import com.metromind.network.MetroNetwork;
import com.metromind.sim.ClosureResponse;
import com.metromind.sim.Departure;
import com.metromind.sim.HeadwayStrategy;
import com.metromind.sim.SimulationService;
import com.metromind.sim.SimulationSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
     * Strategy benchmark: runs every headway strategy against the same {@code scenarios} random incidents, drawn
     * from {@code seed}, and ranks them by mean and p95 passenger wait. {@code kinds} (comma-separated, e.g.
     * {@code DELAY,STATION_CLOSURE}) limits the incidents drawn; by default train delays, breakdowns, track-section
     * closures and station closures are all used. {@code metric} picks the ranking: {@code WAIT_PER_BOARDING}
     * (default) or {@code TRAVEL_TIME}. Does not touch live state.
     */
    @Tag(name = "Analysis")
    @Operation(summary = "Rank strategies over random incident scenarios")
    @PostMapping("/analysis/benchmark")
    public BenchmarkReport benchmark(@RequestParam(defaultValue = "10") @Min(1) @Max(50) int scenarios,
                                     @RequestParam(defaultValue = "1") long seed,
                                     @RequestParam(defaultValue = "1800") @Min(300) @Max(3600) double horizonSeconds,
                                     @RequestParam(required = false) Set<BenchmarkReport.IncidentKind> kinds,
                                     @RequestParam(defaultValue = "WAIT_PER_BOARDING")
                                     BenchmarkReport.RankingMetric metric) {
        Set<BenchmarkReport.IncidentKind> drawn = kinds == null || kinds.isEmpty()
                ? EnumSet.allOf(BenchmarkReport.IncidentKind.class) : EnumSet.copyOf(kinds);
        return simulation.benchmarkStrategies(scenarios, seed, horizonSeconds, drawn, metric);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
