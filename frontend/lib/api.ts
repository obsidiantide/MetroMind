import type {
  BenchmarkJob, BenchmarkRequest, ClosureResponse, DelayImpactReport, Departure, IncidentImpactReport, MetroNetwork,
  MinuteKpi,
} from "./types";

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
  submitBenchmark: (r: BenchmarkRequest) => {
    const q = new URLSearchParams({
      scenarios: String(r.scenarios), seed: String(r.seed), horizonSeconds: String(r.horizonSeconds), metric: r.metric,
    });
    if (r.kinds.length > 0) q.set("kinds", r.kinds.join(","));
    if (r.runTimeVariability !== undefined) q.set("runTimeVariability", String(r.runTimeVariability));
    return call<BenchmarkJob>(`/api/analysis/benchmark/jobs?${q}`, { method: "POST" });
  },
  benchmarkJob: (id: string) => call<BenchmarkJob>(`/api/analysis/benchmark/jobs/${encodeURIComponent(id)}`),
  benchmarkJobs: () => call<BenchmarkJob[]>("/api/analysis/benchmark/jobs"),
  /**
   * Cancels a queued or running benchmark job and returns it as it now stands. A job that had already finished is
   * returned unchanged (the server answers 409 with it), so the caller can simply show whatever comes back.
   */
  cancelBenchmarkJob: async (id: string): Promise<BenchmarkJob> => {
    const res = await fetch(`${API_URL}/api/analysis/benchmark/jobs/${encodeURIComponent(id)}`, { method: "DELETE" });
    if (!res.ok && res.status !== 409) throw new Error(`${res.status} ${await res.text()}`);
    return (await res.json()) as BenchmarkJob;
  },
};
