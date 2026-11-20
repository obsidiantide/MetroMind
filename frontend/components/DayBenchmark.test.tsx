import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";
import { DayBenchmark, parseTimes } from "@/components/DayBenchmark";
import { api } from "@/lib/api";
import type { BenchmarkRequest, DayBenchmarkJob, TimeOfDayReport } from "@/lib/types";
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

function dayJob(over: Partial<DayBenchmarkJob> = {}): DayBenchmarkJob {
  return {
    id: "day-1", status: "SUCCEEDED", completedRuns: 32, totalRuns: 32, progress: 1,
    submittedAt: "2026-11-19T08:00:00Z", startedAt: "2026-11-19T08:00:01Z", finishedAt: "2026-11-19T08:00:30Z",
    report: day, error: null, ...over,
  };
}

const running = dayJob({ status: "RUNNING", completedRuns: 8, progress: 0.25, finishedAt: null, report: null });

describe("DayBenchmark", () => {
  beforeEach(() => {
    vi.spyOn(api, "dayBenchmarkJobs").mockResolvedValue([]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("splits typed start times and drops blanks", () => {
    expect(parseTimes(" 07:30,12:00 , ,22:00")).toEqual(["07:30", "12:00", "22:00"]);
    expect(parseTimes(" ")).toEqual([]);
  });

  it("queues a ranking across the typed times of day and shows it once the job is done", async () => {
    vi.useFakeTimers();
    const submit = vi.spyOn(api, "submitDayBenchmark").mockResolvedValue(running);
    const poll = vi.spyOn(api, "dayBenchmarkJob").mockResolvedValue(dayJob());
    render(<DayBenchmark request={() => request} />);

    fireEvent.change(screen.getByLabelText("Times of day"), { target: { value: "08:00, 22:30" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Rank across the day" }));
    });

    expect(submit).toHaveBeenCalledWith(request, ["08:00", "22:30"]);
    expect(screen.getByText("Running: 8 of 32 runs")).toBeTruthy();
    expect((screen.getByRole("button", { name: "Rank across the day" }) as HTMLButtonElement).disabled).toBe(true);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });

    expect(poll).toHaveBeenCalledWith("day-1");
    expect(screen.getByText("22:30 (#2)")).toBeTruthy();
    const rows = screen.getAllByRole("row");
    expect(rows[1].textContent).toContain("BALANCED");
    expect(rows[1].textContent).toContain("101.5 s");
    expect(rows[1].textContent).toContain("#1#2");
    expect(rows[2].textContent).toContain("08:00 (#2)");
  });

  it("cancels a running ranking", async () => {
    vi.spyOn(api, "submitDayBenchmark").mockResolvedValue(running);
    const cancel = vi.spyOn(api, "cancelDayBenchmarkJob")
      .mockResolvedValue(dayJob({ status: "CANCELLED", completedRuns: 8, progress: 0.25, report: null }));
    render(<DayBenchmark request={() => request} />);

    fireEvent.click(screen.getByRole("button", { name: "Rank across the day" }));
    fireEvent.click(await screen.findByRole("button", { name: "Cancel" }));

    expect(await screen.findByText("Ranking cancelled after 8 of 32 runs.")).toBeTruthy();
    expect(cancel).toHaveBeenCalledWith("day-1");
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("exports the ranking across the day as a CSV file", async () => {
    vi.spyOn(api, "submitDayBenchmark").mockResolvedValue(dayJob());
    const created: Blob[] = [];
    const original = { create: URL.createObjectURL, revoke: URL.revokeObjectURL };
    onTestFinished(() => { URL.createObjectURL = original.create; URL.revokeObjectURL = original.revoke; });
    URL.createObjectURL = vi.fn((blob: Blob) => { created.push(blob); return "blob:day"; });
    URL.revokeObjectURL = vi.fn();
    const clicked: HTMLAnchorElement[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(this);
    });
    render(<DayBenchmark request={() => request} />);

    fireEvent.click(screen.getByRole("button", { name: "Rank across the day" }));
    fireEvent.click(await screen.findByRole("button", { name: "Export CSV" }));

    expect(clicked).toHaveLength(1);
    expect(clicked[0].download).toBe("benchmark-day-seed9-wait-per-boarding.csv");
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.readAsText(created[0]);
    });
    expect(text.split("\r\n")[1]).toBe("08:00,1,BALANCED,101,151,9001,9501,600,0,1");
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:day");
  });

  it("lists recent rankings and reopens a finished one", async () => {
    vi.spyOn(api, "dayBenchmarkJobs").mockResolvedValue([dayJob(), { ...running, id: "day-2" }]);
    render(<DayBenchmark request={() => request} />);

    const recent = await screen.findByRole("table", { name: "Recent rankings across the day" });
    const rows = within(recent).getAllByRole("row");
    expect(rows[1].textContent).toContain("SUCCEEDED");
    expect(rows[1].textContent).toContain("08:00, 22:30");
    expect(rows[1].textContent).toContain("BALANCED");
    expect(rows[2].textContent).toContain("RUNNING");
    expect(rows[2].textContent).toContain("8/32");

    fireEvent.click(within(rows[1]).getByRole("button", { name: "Show" }));

    expect(screen.getByText("22:30 (#2)")).toBeTruthy();
    expect(within(rows[1]).queryByRole("button", { name: "Show" })).toBeNull();
  });

  it("resumes polling a running job reopened from the list", async () => {
    vi.useFakeTimers();
    vi.spyOn(api, "dayBenchmarkJobs").mockResolvedValue([running]);
    const poll = vi.spyOn(api, "dayBenchmarkJob").mockResolvedValue(dayJob());
    render(<DayBenchmark request={() => request} />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    fireEvent.click(screen.getByRole("button", { name: "Show" }));
    expect(screen.getByText("Running: 8 of 32 runs")).toBeTruthy();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });

    expect(poll).toHaveBeenCalledWith("day-1");
    expect(screen.getByText("22:30 (#2)")).toBeTruthy();
    // The finished job refreshes the list, so it shows its new status.
    expect(api.dayBenchmarkJobs).toHaveBeenCalledTimes(2);
  });

  it("shows the error when the backend rejects the times", async () => {
    vi.spyOn(api, "submitDayBenchmark").mockRejectedValue(new Error("400: Start time 00:10 leaves no room"));
    render(<DayBenchmark request={() => request} />);
    fireEvent.click(screen.getByRole("button", { name: "Rank across the day" }));
    expect(await screen.findByText(/leaves no room/)).toBeTruthy();
  });
});
