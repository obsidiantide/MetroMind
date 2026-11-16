package com.metromind.api;

import com.metromind.replay.SnapshotHistory;
import com.metromind.sim.SimulationSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Time travel over the last minutes of the live simulation. */
@RestController
@Tag(name = "Replay")
@RequestMapping("/api/replay")
public class ReplayController {

    private final SnapshotHistory history;

    public ReplayController(SnapshotHistory history) {
        this.history = history;
    }

    /** The span of simulated time that can be replayed; {@code 204} before the first snapshot is kept. */
    @Operation(summary = "Span of simulated time that can be replayed")
    @ApiResponse(responseCode = "200", description = "Oldest and newest replayable simulated time")
    @ApiResponse(responseCode = "204", description = "No snapshot kept yet", content = @Content)
    @GetMapping("/range")
    public ResponseEntity<SnapshotHistory.Coverage> range() {
        return history.coverage().map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** The snapshot as it was at simulated time {@code at} (the newest kept one at or before it). */
    @Operation(summary = "Snapshot as it was at a past simulated time")
    @ApiResponse(responseCode = "200", description = "The newest kept snapshot at or before that time",
            content = @Content(schema = @Schema(implementation = SimulationSnapshot.class)))
    @ApiResponse(responseCode = "404", description = "Nothing kept at or before that time")
    @GetMapping
    public ResponseEntity<?> at(@RequestParam double at) {
        return history.at(at).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error",
                        "No snapshot at or before %.0f; see /api/replay/range for what is kept".formatted(at))));
    }
}
