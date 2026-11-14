import type { ClosureResponse, DelayImpactReport, MetroNetwork } from "./types";

export const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
export const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? API_URL.replace(/^http/, "ws") + "/ws";

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(API_URL + path, {
    ...init,
    headers: { "content-type": "application/json", ...(init?.headers ?? {}) },
  });
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  return res.status === 204 || res.status === 202 ? (undefined as T) : res.json();
}

export const api = {
  network: () => call<MetroNetwork>("/api/network"),
  strategies: () => call<string[]>("/api/strategies"),
  setStrategy: (strategy: string) => call<void>("/api/strategy", { method: "PUT", body: JSON.stringify({ strategy }) }),
  delay: (trainId: string, seconds: number) =>
    call<void>("/api/incidents/delay", { method: "POST", body: JSON.stringify({ trainId, seconds }) }),
  breakdown: (trainId: string, seconds: number) =>
    call<void>("/api/incidents/breakdown", { method: "POST", body: JSON.stringify({ trainId, seconds }) }),
  closeSection: (lineId: string, fromStationId: string, toStationId: string, seconds: number, response: ClosureResponse) =>
    call<void>("/api/incidents/section-closure", {
      method: "POST",
      body: JSON.stringify({ lineId, fromStationId, toStationId, seconds, response }),
    }),
  closeStation: (stationId: string, seconds: number) =>
    call<void>("/api/incidents/station-closure", { method: "POST", body: JSON.stringify({ stationId, seconds }) }),
  analyze: (trainId: string, seconds: number) =>
    call<DelayImpactReport>("/api/analysis/delay-impact", { method: "POST", body: JSON.stringify({ trainId, seconds }) }),
};
