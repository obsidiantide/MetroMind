import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DayBenchmark, parseTimes } from "@/components/DayBenchmark";
import { api } from "@/lib/api";
import type { BenchmarkRequest, TimeOfDayReport } from "@/lib/types";
import { ranking, report } from "@/test/benchmark";

const request: BenchmarkRequest = {
  scenarios: 3, seed: 9, horizonSeconds: 1200, kinds: ["DELAY"], metric: "WAIT_PER_BOARDING",
};

const day: TimeOfDayReport = {
  seed: 9, horizonSeconds: 1200, metric: "WAIT_PER_BOARDING",
  periods: [
    { startClock: "08:00", report: { ...report, rankings: [ranking(1, "BALANCED"), ranking(2, "SHORT_TURN")] } },
    { startClock: "22:30", report: { ...report, rankings: [ranking(1, "SHORT_TURN"), ranking(2, "BALANCED")] } },
  ],
  rankings: [
    { rank: 1, strategy: "BALANCED", meanWaitSeconds: 101.5, meanTravelMinutes: 9001, worstStartClock: "22:30", worstRank: 2 },
    { rank: 2, strategy: "SHORT_TURN", meanWaitSeconds: 101.5, meanTravelMinutes: 9002, worstStartClock: "08:00", worstRank: 2 },
  ],
};

describe("DayBenchmark", () => {
  afterEach(() => vi.restoreAllMocks());

  it("splits typed start times and drops blanks", () => {
    expect(parseTimes(" 07:30,12:00 , ,22:00")).toEqual(["07:30", "12:00", "22:00"]);
    expect(parseTimes(" ")).toEqual([]);
  });

  it("ranks across the typed times of day with the page's settings", async () => {
    const run = vi.spyOn(api, "benchmarkDay").mockResolvedValue(day);
    render(<DayBenchmark request={() => request} />);

    fireEvent.change(screen.getByLabelText("Times of day"), { target: { value: "08:00, 22:30" } });
    fireEvent.click(screen.getByRole("button", { name: "Rank across the day" }));

    expect(await screen.findByText("22:30 (#2)")).toBeTruthy();
    expect(run).toHaveBeenCalledWith(request, ["08:00", "22:30"]);
    const rows = screen.getAllByRole("row");
    expect(rows[1].textContent).toContain("BALANCED");
    expect(rows[1].textContent).toContain("101.5 s");
    expect(rows[1].textContent).toContain("#1#2");
    expect(rows[2].textContent).toContain("08:00 (#2)");
  });

  it("shows the error when the backend rejects the times", async () => {
    vi.spyOn(api, "benchmarkDay").mockRejectedValue(new Error("400: Start time 00:10 leaves no room"));
    render(<DayBenchmark request={() => request} />);
    fireEvent.click(screen.getByRole("button", { name: "Rank across the day" }));
    expect(await screen.findByText(/leaves no room/)).toBeTruthy();
  });
});
