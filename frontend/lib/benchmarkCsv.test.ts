import { describe, expect, it } from "vitest";
import { benchmarkCsv, benchmarkCsvName, dayBenchmarkCsv, dayBenchmarkCsvName } from "@/lib/benchmarkCsv";
import type { TimeOfDayReport } from "@/lib/types";
import { ranking, report } from "@/test/benchmark";

describe("benchmarkCsv", () => {
  it("writes one row per strategy in rank order under a header", () => {
    const lines = benchmarkCsv(report).split("\r\n");

    expect(lines[0]).toBe("rank,strategy,mean_wait_s,p95_wait_s,mean_travel_min,p95_travel_min,mean_recovery_s,"
      + "unrecovered_scenarios,mean_short_turns,mean_stops_skipped,mean_passengers_carried_past");
    expect(lines[1]).toBe("1,BALANCED,101,151,9001,9501,600,0,0,0,0");
    expect(lines[2]).toBe("2,SHORT_TURN,102,152,9002,9502,,2,1.5,0,0");
    expect(lines[3]).toBe("3,SKIP_STOP,103,153,9003,9503,600,0,0,2.5,41.6");
    expect(lines).toHaveLength(5);
    expect(lines[4]).toBe("");
  });

  it("rounds to three decimals and quotes fields that need it", () => {
    const csv = benchmarkCsv({ ...report, rankings: [ranking(1, 'ODD, "QUOTED"', { meanWaitSeconds: 12.34567 })] });

    expect(csv.split("\r\n")[1]).toMatch(/^1,"ODD, ""QUOTED""",12\.346,/);
  });

  it("adds a trunk CV column per branched line, blank where a strategy has no value", () => {
    const csv = benchmarkCsv({
      ...report,
      rankings: [ranking(1, "BALANCED", { meanTrunkHeadwayCv: { M1: 0.12345, M3: 0.2 } }),
        ranking(2, "NO_CONTROL", { meanTrunkHeadwayCv: { M1: 0.4 } })],
    }).split("\r\n");

    expect(csv[0]).toMatch(/,mean_passengers_carried_past,trunk_cv_M1,trunk_cv_M3$/);
    expect(csv[1]).toMatch(/,0\.123,0\.2$/);
    expect(csv[2]).toMatch(/,0\.4,$/);
  });

  it("names the file after the seed and ranking metric", () => {
    expect(benchmarkCsvName(report)).toBe("benchmark-seed7-wait-per-boarding.csv");
    expect(benchmarkCsvName({ ...report, seed: 3, metric: "TRAVEL_TIME" })).toBe("benchmark-seed3-travel-time.csv");
  });
});

describe("dayBenchmarkCsv", () => {
  const day: TimeOfDayReport = {
    seed: 7, horizonSeconds: 1800, metric: "TRAVEL_TIME",
    periods: [
      { startClock: "08:00", report: { ...report, rankings: [ranking(1, "BALANCED"), ranking(2, "SHORT_TURN")] } },
      { startClock: "22:30", report: { ...report, rankings: [
        ranking(1, "SHORT_TURN", { meanRecoverySeconds: null, unrecoveredScenarios: 1 }), ranking(2, "BALANCED")] } },
    ],
    rankings: [
      { rank: 1, strategy: "SHORT_TURN", meanWaitSeconds: 101.5, meanTravelMinutes: 9001.5, worstStartClock: "08:00", worstRank: 2 },
      { rank: 2, strategy: "BALANCED", meanWaitSeconds: 101.5, meanTravelMinutes: 9001.5, worstStartClock: "22:30", worstRank: 2 },
    ],
  };

  it("writes one row per period and strategy, in run order and period rank, with the day rank", () => {
    const lines = dayBenchmarkCsv(day).split("\r\n");

    expect(lines[0]).toBe("start_clock,period_rank,strategy,mean_wait_s,p95_wait_s,mean_travel_min,p95_travel_min,"
      + "mean_recovery_s,unrecovered_scenarios,day_rank");
    expect(lines[1]).toBe("08:00,1,BALANCED,101,151,9001,9501,600,0,2");
    expect(lines[2]).toBe("08:00,2,SHORT_TURN,102,152,9002,9502,600,0,1");
    expect(lines[3]).toBe("22:30,1,SHORT_TURN,101,151,9001,9501,,1,1");
    expect(lines[4]).toBe("22:30,2,BALANCED,102,152,9002,9502,600,0,2");
    expect(lines).toHaveLength(6);
    expect(lines[5]).toBe("");
  });

  it("names the file after the seed and ranking metric", () => {
    expect(dayBenchmarkCsvName(day)).toBe("benchmark-day-seed7-travel-time.csv");
  });
});
