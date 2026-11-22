import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";
import BenchmarkPage from "@/app/benchmark/page";
import { api } from "@/lib/api";
import { job } from "@/test/benchmark";
import { network } from "@/test/fixtures";

describe("BenchmarkPage", () => {
  beforeEach(() => {
    vi.spyOn(api, "network").mockResolvedValue(network);
    vi.spyOn(api, "benchmarkJobs").mockResolvedValue([]);
    vi.spyOn(api, "dayBenchmarkJobs").mockResolvedValue([]);
  });
  afterEach(() => vi.restoreAllMocks());

  it("submits the chosen settings and polls the job until its ranking arrives", async () => {
    const submit = vi.spyOn(api, "submitBenchmark")
      .mockResolvedValue(job({ status: "RUNNING", completedRuns: 4, progress: 0.25, report: null, finishedAt: null }));
    vi.spyOn(api, "benchmarkJob").mockResolvedValue(job());
    render(<BenchmarkPage />);

    fireEvent.change(screen.getByLabelText("Scenarios"), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText("Seed"), { target: { value: "7" } });
    fireEvent.click(screen.getByLabelText("Breakdowns"));
    fireEvent.change(screen.getByLabelText(/Rank by/), { target: { value: "TRAVEL_TIME" } });
    fireEvent.click(screen.getByRole("button", { name: "Run benchmark" }));

    expect(await screen.findByText("Running: 4 of 16 runs")).toBeTruthy();
    expect(submit).toHaveBeenCalledWith({
      scenarios: 2, seed: 7, horizonSeconds: 1800,
      kinds: ["DELAY", "DOOR_FAULT", "SECTION_CLOSURE", "STATION_CLOSURE", "POWER_FAILURE"],
      metric: "TRAVEL_TIME",
    });
    expect(screen.getByRole("button", { name: "Run benchmark" })).toHaveProperty("disabled", true);

    expect(await screen.findByText(/Ranking · 2 scenarios · seed 7/, {}, { timeout: 3000 })).toBeTruthy();
    expect(screen.queryByText(/Running:/)).toBeNull();
  });

  it("sends a run-time spread when one is set and shows it on the ranking", async () => {
    const submit = vi.spyOn(api, "submitBenchmark")
      .mockResolvedValue(job({ report: { ...job().report!, runTimeVariability: 0.08 } }));
    render(<BenchmarkPage />);

    fireEvent.change(screen.getByLabelText(/Run-time spread/), { target: { value: "8" } });
    fireEvent.click(screen.getByRole("button", { name: "Run benchmark" }));

    expect(await screen.findByText(/run times ±8 %/)).toBeTruthy();
    expect(submit.mock.calls[0][0].runTimeVariability).toBeCloseTo(0.08);
  });

  it("sends a dwell spread when one is set and shows it on the ranking", async () => {
    const submit = vi.spyOn(api, "submitBenchmark")
      .mockResolvedValue(job({ report: { ...job().report!, dwellVariability: 0.15 } }));
    render(<BenchmarkPage />);

    fireEvent.change(screen.getByLabelText(/Dwell spread/), { target: { value: "15" } });
    fireEvent.click(screen.getByRole("button", { name: "Run benchmark" }));

    expect(await screen.findByText(/dwells ±15 %/)).toBeTruthy();
    expect(submit.mock.calls[0][0].dwellVariability).toBeCloseTo(0.15);
    expect(submit.mock.calls[0][0].runTimeVariability).toBeUndefined();
  });

  it("sends a share of late reliefs when one is set and shows it on the ranking", async () => {
    const submit = vi.spyOn(api, "submitBenchmark").mockResolvedValue(
      job({ report: { ...job().report!, reliefOverrunShare: 0.2, reliefOverrunSeconds: 90 } }));
    render(<BenchmarkPage />);

    fireEvent.change(screen.getByLabelText(/Late reliefs/), { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: "Run benchmark" }));

    expect(await screen.findByText(/late reliefs 20 % \(~90 s\)/)).toBeTruthy();
    expect(submit.mock.calls[0][0].reliefOverrunShare).toBeCloseTo(0.2);
    expect(submit.mock.calls[0][0].dwellVariability).toBeUndefined();
  });

  it("ranks strategies with recovery, short turns and skipped stops, and names scenario places", async () => {
    vi.spyOn(api, "benchmarkJobs").mockResolvedValue([job()]);
    render(<BenchmarkPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Show" }));

    // BALANCED also heads the recent-runs table; in the ranking its row is highlighted as the best.
    expect(screen.getAllByText("BALANCED").map((cell) => cell.closest("tr")?.className)).toContain("best");
    expect(screen.getByText("never (2/2 not recovered)")).toBeTruthy();
    expect(screen.getByText("1.5")).toBeTruthy();
    expect(screen.getByText("2.5 (42 carried past)")).toBeTruthy();
    expect(screen.getByText("RED-1 delayed 4m 00s")).toBeTruthy();
    expect(await screen.findByText("Red Line Alpha – Central closed 15m 00s, trains turn back")).toBeTruthy();
  });

  it("names the line of a power failure scenario", async () => {
    const withOutage = job({ report: { ...job().report!, scenarios: [{ kind: "POWER_FAILURE", seconds: 300, lineId: "RED" }] } });
    vi.spyOn(api, "benchmarkJobs").mockResolvedValue([withOutage]);
    render(<BenchmarkPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Show" }));

    expect(await screen.findByText("Red Line without traction power 5m 00s")).toBeTruthy();
  });

  it("shows each branched line's trunk regularity when the network has one", async () => {
    const report = job().report!;
    const withTrunk = {
      ...report,
      rankings: report.rankings.map((r, i) => ({ ...r, meanTrunkHeadwayCv: { RED: 0.1 * (i + 1) } })),
    };
    vi.spyOn(api, "benchmarkJobs").mockResolvedValue([job({ report: withTrunk })]);
    render(<BenchmarkPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Show" }));

    expect(screen.getByText("Trunk CV")).toBeTruthy();
    expect(await screen.findByText("Red Line 0.20")).toBeTruthy();
  });

  it("leaves the trunk column out for a network without branches", async () => {
    vi.spyOn(api, "benchmarkJobs").mockResolvedValue([job()]);
    render(<BenchmarkPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Show" }));

    expect(screen.queryByText("Trunk CV")).toBeNull();
  });

  it("exports the shown ranking as a CSV file", async () => {
    vi.spyOn(api, "benchmarkJobs").mockResolvedValue([job()]);
    const created: Blob[] = [];
    const original = { create: URL.createObjectURL, revoke: URL.revokeObjectURL };
    onTestFinished(() => { URL.createObjectURL = original.create; URL.revokeObjectURL = original.revoke; });
    URL.createObjectURL = vi.fn((blob: Blob) => { created.push(blob); return "blob:report"; });
    URL.revokeObjectURL = vi.fn();
    const clicked: HTMLAnchorElement[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(this);
    });
    render(<BenchmarkPage />);

    fireEvent.click(await screen.findByRole("button", { name: "Show" }));
    fireEvent.click(screen.getByRole("button", { name: "Export CSV" }));

    expect(clicked).toHaveLength(1);
    expect(clicked[0].download).toBe("benchmark-seed7-wait-per-boarding.csv");
    expect(clicked[0].href).toBe("blob:report");
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.readAsText(created[0]);
    });
    expect(text).toContain("1,BALANCED,101,151,");
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:report");
  });

  it("cancels a running job and says how far it got", async () => {
    vi.spyOn(api, "submitBenchmark")
      .mockResolvedValue(job({ status: "QUEUED", completedRuns: 0, progress: 0, report: null }));
    vi.spyOn(api, "benchmarkJob").mockResolvedValue(job({ status: "RUNNING", completedRuns: 3, report: null }));
    const cancel = vi.spyOn(api, "cancelBenchmarkJob")
      .mockResolvedValue(job({ status: "CANCELLED", completedRuns: 5, report: null }));
    render(<BenchmarkPage />);

    fireEvent.click(screen.getByRole("button", { name: "Run benchmark" }));
    fireEvent.click(await screen.findByRole("button", { name: "Cancel" }));

    expect(await screen.findByText("Benchmark cancelled after 5 of 16 runs.")).toBeTruthy();
    expect(cancel).toHaveBeenCalledWith("job-1");
  });

  it("cannot run without any incident kind and shows submission errors", async () => {
    vi.spyOn(api, "submitBenchmark").mockRejectedValue(new Error("400 scenarios must be positive"));
    render(<BenchmarkPage />);

    const run = screen.getByRole("button", { name: "Run benchmark" });
    for (const kind of ["Train delays", "Breakdowns", "Door faults", "Track closures", "Station closures",
      "Power failures"]) {
      fireEvent.click(screen.getByLabelText(kind));
    }
    expect(run).toHaveProperty("disabled", true);

    fireEvent.click(screen.getByLabelText("Train delays"));
    fireEvent.click(run);
    await waitFor(() => expect(screen.getByText("Error: 400 scenarios must be positive")).toBeTruthy());
  });
});
