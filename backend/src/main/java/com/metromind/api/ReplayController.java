package com.metromind.api;

import com.metromind.replay.SnapshotHistory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Time travel over the last minutes of the live simulation. */
@RestController
@RequestMapping("/api/replay")
public class ReplayController {

    private final SnapshotHistory history;

    public ReplayController(SnapshotHistory history) {
        this.history = history;
    }

    /** The span of simulated time that can be replayed; {@code 204} before the first snapshot is kept. */
    @GetMapping("/range")
    public ResponseEntity<SnapshotHistory.Coverage> range() {
        return history.coverage().map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** The snapshot as it was at simulated time {@code at} (the newest kept one at or before it). */
    @GetMapping
    public ResponseEntity<?> at(@RequestParam double at) {
        return history.at(at).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error",
                        "No snapshot at or before %.0f; see /api/replay/range for what is kept".formatted(at))));
    }
}
