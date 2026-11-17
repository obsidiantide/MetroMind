import type { BenchmarkJob, BenchmarkReport, StrategyRanking } from "@/lib/types";

export function ranking(rank: number, strategy: string, over: Partial<StrategyRanking> = {}): StrategyRanking {
  return {
    rank, strategy, meanWaitSeconds: 100 + rank, p95WaitSeconds: 150 + rank, meanTravelMinutes: 9000 + rank,
    p95TravelMinutes: 9500 + rank, meanRecoverySeconds: 600, unrecoveredScenarios: 0, meanShortTurns: 0,
    shortTurnsByStation: {}, meanStopsSkipped: 0, meanPassengersCarriedPast: 0, stopsSkippedByStation: {},
    waitSecondsPerScenario: [100, 102], ...over,
  };
}

export const report: BenchmarkReport = {
  seed: 7, horizonSeconds: 1800, metric: "WAIT_PER_BOARDING",
  scenarios: [
    { kind: "DELAY", seconds: 240, trainId: "RED-1" },
    { kind: "SECTION_CLOSURE", seconds: 900, lineId: "RED", fromStationId: "A", toStationId: "C", response: "TURN_BACK" },
  ],
  rankings: [
    ranking(1, "BALANCED"),
    ranking(2, "SHORT_TURN", { meanShortTurns: 1.5, meanRecoverySeconds: null, unrecoveredScenarios: 2 }),
    ranking(3, "SKIP_STOP", { meanStopsSkipped: 2.5, meanPassengersCarriedPast: 41.6 }),
  ],
};

export function job(over: Partial<BenchmarkJob> = {}): BenchmarkJob {
  return {
    id: "job-1", status: "SUCCEEDED", completedRuns: 16, totalRuns: 16, progress: 1,
    submittedAt: "2026-11-17T08:00:00Z", startedAt: "2026-11-17T08:00:01Z", finishedAt: "2026-11-17T08:00:09Z",
    report, error: null, ...over,
  };
}
