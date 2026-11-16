package com.metromind.kpi;

import java.util.Map;

/**
 * How far KPI aggregation has got, to spot a lagging telemetry topic.
 *
 * @param watermarks  newest simulated time seen per telemetry source; a source that has sent nothing is absent
 * @param openMinutes minutes still waiting for a slower source before they can close
 * @param lateSamples samples dropped because their minute had already closed
 */
public record KpiPipelineStatus(Map<KpiAggregator.Source, Double> watermarks, int openMinutes, long lateSamples) {
}
