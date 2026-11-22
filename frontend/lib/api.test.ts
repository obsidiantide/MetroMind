import { afterEach, describe, expect, it, vi } from "vitest";
import { api, API_URL } from "@/lib/api";
import type { BenchmarkRequest, DayBenchmarkJob } from "@/lib/types";

const request: BenchmarkRequest = {
  scenarios: 3, seed: 9, horizonSeconds: 1200, kinds: ["DELAY", "DOOR_FAULT"], metric: "TRAVEL_TIME",
  dwellVariability: 0.1, reliefOverrunShare: 0.2,
};

const queued: DayBenchmarkJob = {
  id: "day-1", status: "QUEUED", completedRuns: 0, totalRuns: 48, progress: 0,
  submittedAt: "2026-11-19T08:00:00Z", startedAt: null, finishedAt: null, report: null, error: null,
};

function respond(status: number, body: unknown) {
  return vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } }));
}

describe("day benchmark jobs", () => {
  afterEach(() => vi.restoreAllMocks());

  it("queues a job with the page's settings and the start times", async () => {
    const fetch = respond(202, queued);

    expect(await api.submitDayBenchmark(request, ["08:00", "22:30"])).toEqual(queued);

    const url = new URL(fetch.mock.calls[0][0] as string);
    expect(url.href.startsWith(`${API_URL}/api/analysis/benchmark/day/jobs?`)).toBe(true);
    expect(fetch.mock.calls[0][1]?.method).toBe("POST");
    expect(Object.fromEntries(url.searchParams)).toEqual({
      scenarios: "3", seed: "9", horizonSeconds: "1200", metric: "TRAVEL_TIME", kinds: "DELAY,DOOR_FAULT",
      dwellVariability: "0.1", reliefOverrunShare: "0.2", startTimes: "08:00,22:30",
    });
  });

  it("lists the jobs the server keeps", async () => {
    const fetch = respond(200, [queued]);

    expect(await api.dayBenchmarkJobs()).toEqual([queued]);
    expect(fetch.mock.calls[0][0]).toBe(`${API_URL}/api/analysis/benchmark/day/jobs`);
  });

  it("returns a job that had already finished when cancelling it", async () => {
    const done: DayBenchmarkJob = { ...queued, status: "SUCCEEDED", completedRuns: 48, progress: 1 };
    const fetch = respond(409, done);

    expect(await api.cancelDayBenchmarkJob("day/1")).toEqual(done);
    expect(fetch.mock.calls[0][0]).toBe(`${API_URL}/api/analysis/benchmark/day/jobs/day%2F1`);
    expect(fetch.mock.calls[0][1]?.method).toBe("DELETE");
  });

  it("throws on other errors when cancelling", async () => {
    respond(404, { error: "no such job" });
    await expect(api.cancelDayBenchmarkJob("gone")).rejects.toThrow(/404/);
  });
});

describe("power failures", () => {
  afterEach(() => vi.restoreAllMocks());

  it("cuts a whole line, or only a feeder section when a stretch is given", async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(null, { status: 202 }));

    await api.cutPower("RED", 300);
    await api.cutPower("RED", 300, { fromStationId: "R5", toStationId: "R7" });

    expect(fetch.mock.calls[0][0]).toBe(`${API_URL}/api/incidents/power-failure`);
    expect(JSON.parse(fetch.mock.calls[0][1]?.body as string)).toEqual({ lineId: "RED", seconds: 300 });
    expect(JSON.parse(fetch.mock.calls[1][1]?.body as string))
      .toEqual({ lineId: "RED", seconds: 300, fromStationId: "R5", toStationId: "R7" });
  });
});
