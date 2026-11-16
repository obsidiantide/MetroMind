package com.metromind.api;

import com.metromind.kpi.KpiRecorder;
import com.metromind.kpi.MinuteKpi;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "KPIs")
@RequestMapping("/api")
public class KpiController {

    private final KpiRecorder kpis;

    public KpiController(KpiRecorder kpis) {
        this.kpis = kpis;
    }

    /** Per-minute KPIs aggregated from the telemetry stream: the last {@code limit} closed minutes, oldest first. */
    @Operation(summary = "Last closed minutes of KPIs, oldest first")
    @GetMapping("/kpis")
    public List<MinuteKpi> kpis(@RequestParam(defaultValue = "60") @Min(1) @Max(1440) int limit) {
        return kpis.recent(limit);
    }
}
