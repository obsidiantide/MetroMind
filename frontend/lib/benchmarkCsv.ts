import type { BenchmarkReport } from "@/lib/types";

const COLUMNS = [
  "rank", "strategy", "mean_wait_s", "p95_wait_s", "mean_travel_min", "p95_travel_min", "mean_recovery_s",
  "unrecovered_scenarios", "mean_short_turns", "mean_stops_skipped", "mean_passengers_carried_past",
] as const;

/** Quotes a CSV field when it holds a comma, quote or line break (RFC 4180). */
function field(value: string | number | null): string {
  if (value === null) return "";
  const text = typeof value === "number" ? String(Math.round(value * 1000) / 1000) : value;
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/**
 * A benchmark's strategy ranking as CSV, one row per strategy in rank order, with raw numbers (seconds and
 * minutes, rounded to 3 decimals) so it can be charted or compared in a spreadsheet. A strategy that never
 * recovered within the horizon has an empty `mean_recovery_s`.
 */
export function benchmarkCsv(report: BenchmarkReport): string {
  const rows = report.rankings.map((r) => [
    r.rank, r.strategy, r.meanWaitSeconds, r.p95WaitSeconds, r.meanTravelMinutes, r.p95TravelMinutes,
    r.meanRecoverySeconds, r.unrecoveredScenarios, r.meanShortTurns, r.meanStopsSkipped, r.meanPassengersCarriedPast,
  ].map(field).join(","));
  return [COLUMNS.join(","), ...rows].join("\r\n") + "\r\n";
}

/** File name for a report's CSV, e.g. `benchmark-seed7-wait-per-boarding.csv`. */
export function benchmarkCsvName(report: BenchmarkReport): string {
  return `benchmark-seed${report.seed}-${report.metric.toLowerCase().replace(/_/g, "-")}.csv`;
}
