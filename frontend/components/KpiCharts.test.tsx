import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { KpiCharts } from "@/components/KpiCharts";
import { api } from "@/lib/api";
import { minuteKpi } from "@/test/fixtures";

describe("KpiCharts", () => {
  afterEach(() => vi.restoreAllMocks());

  it("asks for the last hour and says when no minute has closed yet", async () => {
    const kpis = vi.spyOn(api, "kpis").mockResolvedValue([]);
    render(<KpiCharts />);

    expect(await screen.findByText(/Collecting the first simulated minute/)).toBeTruthy();
    expect(kpis).toHaveBeenCalledWith(60);
  });

  it("draws delay and waiting charts from the aggregated minutes", async () => {
    vi.spyOn(api, "kpis").mockResolvedValue([
      minuteKpi(480, { averageDelaySeconds: 12, maxDelaySeconds: 95, averageWaiting: 310, maxWaiting: 420 }),
      minuteKpi(481, { averageDelaySeconds: 18, maxDelaySeconds: 140, averageWaiting: 330, maxWaiting: 455 }),
    ]);
    render(<KpiCharts />);

    expect(await screen.findByRole("img", { name: "Train delay" })).toBeTruthy();
    expect(screen.getByRole("img", { name: "Passengers waiting" })).toBeTruthy();
    const legends = [...document.querySelectorAll(".chart-legend")].map((l) => l.textContent);
    expect(legends[0]).toContain("worst 140 s");
    expect(legends[1]).toContain("peak 455");
  });

  it("reports a failing KPI endpoint instead of charts", async () => {
    vi.spyOn(api, "kpis").mockRejectedValue(new Error("503 unavailable"));
    render(<KpiCharts />);

    expect(await screen.findByText(/KPIs unavailable: Error: 503 unavailable/)).toBeTruthy();
    expect(screen.queryByRole("img")).toBeNull();
  });
});
