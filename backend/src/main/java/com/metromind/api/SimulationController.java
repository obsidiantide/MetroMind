package com.metromind.api;

import com.metromind.incident.DelayImpactReport;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SimulationController {

    private final MetroNetwork network;
    private final SimulationService simulation;

    public SimulationController(MetroNetwork network, SimulationService simulation) {
        this.network = network;
        this.simulation = simulation;
    }

    @GetMapping("/network")
    public MetroNetwork network() {
        return network;
    }

    @GetMapping("/state")
    public SimulationSnapshot state() {
        return simulation.snapshot();
    }

    /** Live departure board: trains leaving the station in the next {@code horizonSeconds}, soonest first. */
    @GetMapping("/stations/{stationId}/departures")
    public List<Departure> departures(@PathVariable String stationId,
                                      @RequestParam(defaultValue = "1800") @Min(60) @Max(7200) double horizonSeconds) {
        return simulation.departures(stationId, horizonSeconds);
    }

    @GetMapping("/strategies")
    public List<String> strategies() {
        return HeadwayStrategy.all().stream().map(HeadwayStrategy::name).toList();
    }

    @PutMapping("/strategy")
    public ResponseEntity<Void> setStrategy(@RequestBody Map<String, String> body) {
        simulation.setStrategy(body.getOrDefault("strategy", "NO_CONTROL"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/simulation/{action:pause|resume}")
    public ResponseEntity<Void> pause(@PathVariable String action) {
        simulation.setPaused("pause".equals(action));
        return ResponseEntity.noContent().build();
    }

    public record IncidentRequest(@NotBlank String trainId, @Min(1) @Max(3600) double seconds) {
    }

    @PostMapping("/incidents/delay")
    public ResponseEntity<Void> delay(@Valid @RequestBody IncidentRequest req) {
        simulation.injectDelay(req.trainId(), req.seconds());
        return ResponseEntity.accepted().build();
    }

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
    @PostMapping("/incidents/section-closure")
    public ResponseEntity<Void> closeSection(@Valid @RequestBody SectionClosureRequest req) {
        simulation.closeSection(req.lineId(), req.fromStationId(), req.toStationId(), req.seconds(),
                req.response() == null ? ClosureResponse.WAIT : req.response());
        return ResponseEntity.accepted().build();
    }

    /** What-if: impact of delaying a train, compared across all headway strategies. Does not touch live state. */
    @PostMapping("/analysis/delay-impact")
    public DelayImpactReport delayImpact(@Valid @RequestBody IncidentRequest req,
                                         @RequestParam(defaultValue = "1800") @Min(60) @Max(7200) double horizonSeconds) {
        return simulation.analyzeDelay(req.trainId(), req.seconds(), horizonSeconds);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
