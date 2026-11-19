import type {
  BenchmarkJob, BenchmarkRequest, ClosureResponse, DayBenchmarkJob, DelayImpactReport, Departure, IncidentImpactReport,
  MetroNetwork,
  MinuteKpi, TimeOfDayReport,
} from "./types";

/** Query parameters of a benchmark request; spreads left out use the live engine's. */
function benchmarkQuery(r: BenchmarkRequest) {
  const q = new URLSearchParams({
    scenarios: String(r.scenarios), seed: String(r.seed), horizonSeconds: String(r.horizonSeconds), metric: r.metric,
  });
  if (r.kinds.length > 0) q.set("kinds", r.kinds.join(","));
  if (r.runTimeVariability !== undefined) q.set("runTimeVariability", String(r.runTimeVariability));
  if (r.dwellVariability !== undefined) q.set("dwellVariability", String(r.dwellVariability));
  return q;
}

export const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
export const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? API_URL.replace(/^http/, "ws") + "/ws";

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(API_URL + path, {
    ...init,
    headers: { "content-type": "application/json", ...(init?.headers ?? {}) },
  });
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  // 202 Accepted may or may not carry a body (incidents do not, queued benchmark jobs do).
  const body = await res.text();
  return (body ? JSON.parse(body) : undefined) as T;
}

export const api = {
  network: () => call<MetroNetwork>("/api/network"),
  strategies: () => call<string[]>("/api/strategies"),
  /** The last `limit` closed minutes of KPIs, oldest first. */
  kpis: (limit = 60) => call<MinuteKpi[]>(`/api/kpis?limit=${limit}`),
  /** Trains leaving the station within `horizonSeconds` of simulated time, soonest first. */
  departures: (stationId: string, horizonSeconds = 1800) =>
    call<Departure[]>(`/api/stations/${encodeURIComponent(stationId)}/departures?horizonSeconds=${horizonSeconds}`),
  setStrategy: (strategy: string) => call<void>("/api/strategy", { method: "PUT", body: JSON.stringify({ strategy }) }),
  delay: (trainId: string, seconds: number) =>
    call<void>("/api/incidents/delay", { method: "POST", body: JSON.stringify({ trainId, seconds }) }),
  breakdown: (trainId: string, seconds: number) =>
    call<void>("/api/incidents/breakdown", { method: "POST", body: JSON.stringify({ trainId, seconds }) }),
  /** Holds a train at its platform (or the next one it reaches) for `seconds` of extra dwell. */
  doorFault: (trainId: string, seconds: number) =>
    call<void>("/api/incidents/door-fault", { method: "POST", body: JSON.stringify({ trainId, seconds }) }),
  closeSection: (lineId: string, fromStationId: string, toStationId: string, seconds: number, response: ClosureResponse) =>
    call<void>("/api/incidents/section-closure", {
      method: "POST",
      body: JSON.stringify({ lineId, fromStationId, toStationId, seconds, response }),
    }),
  closeStation: (stationId: string, seconds: number) =>
    call<void>("/api/incidents/station-closure", { method: "POST", body: JSON.stringify({ stationId, seconds }) }),
  analyze: (trainId: string, seconds: number) =>
    call<DelayImpactReport>("/api/analysis/delay-impact", { method: "POST", body: JSON.stringify({ trainId, seconds }) }),
  analyzeSectionClosure: (lineId: string, fromStationId: string, toStationId: string, seconds: number,
                          response: ClosureResponse) =>
    call<IncidentImpactReport>("/api/analysis/section-closure-impact", {
      method: "POST",
      body: JSON.stringify({ lineId, fromStationId, toStationId, seconds, response }),
    }),
  analyzeDoorFault: (trainId: string, seconds: number) =>
    call<IncidentImpactReport>("/api/analysis/door-fault-impact", {
      method: "POST",
      body: JSON.stringify({ trainId, seconds }),
    }),
  analyzeStationClosure: (stationId: string, seconds: number) =>
    call<IncidentImpactReport>("/api/analysis/station-closure-impact", {
      method: "POST",
      body: JSON.stringify({ stationId, seconds }),
    }),
  /** Queues a strategy benchmark; poll `benchmarkJob` with the returned id until it has finished. */
  submitBenchmark: (r: BenchmarkRequest) =>
    call<BenchmarkJob>(`/api/analysis/benchmark/jobs?${benchmarkQuery(r)}`, { method: "POST" }),
  /** Ranks strategies across the given times of day (HH:mm); answers once every period has run. */
  benchmarkDay: (r: BenchmarkRequest, startTimes: string[]) => {
    const q = benchmarkQuery(r);
    q.set("startTimes", startTimes.join(","));
    return call<TimeOfDayReport>(`/api/analysis/benchmark/day?${q}`, { method: "POST" });
  },
  benchmarkJob: (id: string) => call<BenchmarkJob>(`/api/analysis/benchmark/jobs/${encodeURIComponent(id)}`),
  benchmarkJobs: () => call<BenchmarkJob[]>("/api/analysis/benchmark/jobs"),
  /**
   * Cancels a queued or running benchmark job and returns it as it now stands. A job that had already finished is
   * returned unchanged (the server answers 409 with it), so the caller can simply show whatever comes back.
   */
  cancelBenchmarkJob: (id: string) => cancelJob<BenchmarkJob>(`/api/analysis/benchmark/jobs/${encodeURIComponent(id)}`),
  /** Queues a benchmark across the given times of day (HH:mm); poll `dayBenchmarkJob` until it has finished. */
  submitDayBenchmark: (r: BenchmarkRequest, startTimes: string[]) => {
    const q = benchmarkQuery(r);
    q.set("startTimes", startTimes.join(","));
    return call<DayBenchmarkJob>(`/api/analysis/benchmark/day/jobs?${q}`, { method: "POST" });
  },
  dayBenchmarkJob: (id: string) =>
    call<DayBenchmarkJob>(`/api/analysis/benchmark/day/jobs/${encodeURIComponent(id)}`),
  /** As `cancelBenchmarkJob`, for a benchmark across times of day. */
  cancelDayBenchmarkJob: (id: string) =>
    cancelJob<DayBenchmarkJob>(`/api/analysis/benchmark/day/jobs/${encodeURIComponent(id)}`),
};

/** DELETE on a job: the job as it now stands, also when it had already finished (409). */
async function cancelJob<J>(path: string): Promise<J> {
  const res = await fetch(`${API_URL}${path}`, { method: "DELETE" });
  if (!res.ok && res.status !== 409) throw new Error(`${res.status} ${await res.text()}`);
  return (await res.json()) as J;
}
