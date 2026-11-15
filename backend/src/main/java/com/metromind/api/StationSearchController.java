package com.metromind.api;

import com.metromind.network.StationLocator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Spatial station search: PostGIS when persistence is on, great-circle distance in memory otherwise. */
@RestController
@RequestMapping("/api/stations")
public class StationSearchController {

    private final StationLocator locator;

    public StationSearchController(StationLocator locator) {
        this.locator = locator;
    }

    /** The {@code limit} stations closest to a WGS84 point, nearest first, with their distance in metres. */
    @GetMapping("/nearest")
    public List<StationLocator.StationDistance> nearest(@RequestParam double lat, @RequestParam double lon,
                                                        @RequestParam(defaultValue = "1") @Min(1) @Max(50)
                                                        int limit) {
        return locator.nearest(lat, lon, limit);
    }

    /** Every station within {@code radiusMeters} of a WGS84 point, nearest first. */
    @GetMapping("/within")
    public List<StationLocator.StationDistance> within(@RequestParam double lat, @RequestParam double lon,
                                                       @RequestParam @Min(1) @Max(50_000) double radiusMeters) {
        return locator.within(lat, lon, radiusMeters);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
