import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DetailDrawer } from "@/components/DetailDrawer";
import { api } from "@/lib/api";
import type { MetroNetwork } from "@/lib/types";
import { network, snapshot, stationView, trainView } from "@/test/fixtures";

describe("DetailDrawer", () => {
  afterEach(() => vi.restoreAllMocks());

  it("lists a branched line among the lines of a station on its branch", async () => {
    vi.spyOn(api, "departures").mockResolvedValue([]);
    // North is on a branch of the Red line from Central, and on the Blue line.
    const branched: MetroNetwork = { ...network, lines: [{ ...network.lines[0], branches: [
      { junctionStationId: "C", stationIds: ["N"], runSeconds: [150], share: 0.5 },
    ] }, network.lines[1]] };

    render(<DetailDrawer selection={{ kind: "station", id: "N" }} network={branched} snapshot={null} onClose={() => {}} />);

    expect(await screen.findByText("Red Line")).toBeTruthy();
    expect(screen.getByText("Blue Line")).toBeTruthy();
  });

  it("says when a station is a flat junction and what crossing it costs", async () => {
    vi.spyOn(api, "departures").mockResolvedValue([]);
    const flat: MetroNetwork = { ...network, lines: [{ ...network.lines[0], branches: [
      { junctionStationId: "C", stationIds: ["N"], runSeconds: [150], share: 0.5, crossingSeconds: 30 },
    ] }, network.lines[1]] };

    render(<DetailDrawer selection={{ kind: "station", id: "C" }} network={flat} snapshot={null} onClose={() => {}} />);

    expect(await screen.findByText(/Flat junction: Red Line trains to North cross the opposite track for 30 s/))
      .toBeTruthy();
  });

  it("shows a station's crowding, platforms and departure board", async () => {
    const departures = vi.spyOn(api, "departures").mockResolvedValue([
      { trainId: "RED-1", lineId: "RED", towardsStationId: "B", plannedTime: 28_860, plannedClock: "08:01:00",
        expectedTime: 28_940, delaySeconds: 80 },
      { trainId: "BLUE-1", lineId: "BLUE", towardsStationId: "N", plannedTime: 28_900, plannedClock: "08:01:40",
        expectedTime: 28_900, delaySeconds: 0 },
    ]);
    const snap = snapshot({ stations: [stationView("C", {
      waiting: 212.4, predictedWaiting15m: 480, overloadRisk: "HIGH", heldAtEntrance: 12, leftBehind: 31,
      queues: [{ lineId: "RED", towardsStationId: "B", waiting: 150 }, { lineId: "BLUE", towardsStationId: "N", waiting: 62 }],
    })] });

    render(<DetailDrawer selection={{ kind: "station", id: "C" }} network={network} snapshot={snap} onClose={() => {}} />);

    expect(screen.getByRole("heading", { name: "Central" })).toBeTruthy();
    // Each line appears as a chip and again as a platform row.
    expect(screen.getAllByText("Red Line")).toHaveLength(2);
    expect(screen.getAllByText("Blue Line")).toHaveLength(2);
    expect(screen.getByText("212 / 400")).toBeTruthy();
    expect(screen.getByText("HIGH")).toBeTruthy();
    expect(screen.getByText("→ North")).toBeTruthy();
    expect(await screen.findByText("+1m 20s")).toBeTruthy();
    expect(screen.getByText("on time")).toBeTruthy();
    // Planned times are shown to the minute.
    expect(screen.getAllByText("08:01")).toHaveLength(2);
    expect(departures).toHaveBeenCalledWith("C");
  });

  it("marks departures bound for a branch terminal", async () => {
    vi.spyOn(api, "departures").mockResolvedValue([
      { trainId: "RED-1", lineId: "RED", towardsStationId: "N", plannedTime: 28_860, plannedClock: "08:01:00",
        expectedTime: 28_860, delaySeconds: 0 },
      { trainId: "RED-2", lineId: "RED", towardsStationId: "B", plannedTime: 28_920, plannedClock: "08:02:00",
        expectedTime: 28_920, delaySeconds: 0 },
    ]);
    const branched: MetroNetwork = { ...network, lines: [{ ...network.lines[0], branches: [
      { junctionStationId: "C", stationIds: ["N"], runSeconds: [150], share: 0.5 },
    ] }] };

    render(<DetailDrawer selection={{ kind: "station", id: "A" }} network={branched} snapshot={null} onClose={() => {}} />);

    const [toNorth, toBravo] = (await screen.findAllByRole("row")).slice(1);
    expect(toNorth.textContent).toContain("North · branch");
    expect(toBravo.textContent).not.toContain("branch");
  });

  it("says where a train is heading, naming a branch", () => {
    const branched: MetroNetwork = { ...network, lines: [{ ...network.lines[0], branches: [
      { junctionStationId: "C", stationIds: ["N"], runSeconds: [150], share: 0.5 },
    ] }] };
    const snap = snapshot({ trains: [trainView("RED-1", { patternId: "RED/N", towardsStationId: "N" })] });
    render(<DetailDrawer selection={{ kind: "train", id: "RED-1" }} network={branched} snapshot={snap} onClose={() => {}} />);

    expect(screen.getByText("to North (branch)")).toBeTruthy();
  });

  it("says when the departure board cannot be loaded", async () => {
    vi.spyOn(api, "departures").mockRejectedValue(new Error("500 boom"));
    render(<DetailDrawer selection={{ kind: "station", id: "A" }} network={network} snapshot={null} onClose={() => {}} />);

    expect(await screen.findByText(/Departures unavailable: Error: 500 boom/)).toBeTruthy();
  });

  it("shows where a train is, its lateness, load and incidents", () => {
    const snap = snapshot({
      trains: [trainView("RED-1", { progress: 0.42, delaySeconds: 185, load: 850, failed: true })],
      activeIncidents: [{ kind: "BREAKDOWN", subjectId: "RED-1", description: "RED-1 broken down", startedAt: 28_700,
        clearsAt: 29_000, remainingSeconds: 125 }],
      recentEvents: [{ simTime: 28_700, type: "INCIDENT", subjectId: "RED-1", message: "RED-1 broke down" }],
    });
    render(<DetailDrawer selection={{ kind: "train", id: "RED-1" }} network={network} snapshot={snap} onClose={() => {}} />);

    expect(screen.getByText("Alpha → Central (42 %)")).toBeTruthy();
    expect(screen.getByText("to Bravo")).toBeTruthy();
    expect(screen.getByText("Running")).toBeTruthy();
    expect(screen.getByText("+3m 05s")).toBeTruthy();
    expect(screen.getByText("broken down")).toBeTruthy();
    expect(screen.getByText(/850 \/ 900/)).toBeTruthy();
    expect(screen.getByText("RED-1 broken down · clears in 2m 05s")).toBeTruthy();
    expect(screen.getByText("RED-1 broke down")).toBeTruthy();
  });

  it("explains a train that is not in service", () => {
    render(<DetailDrawer selection={{ kind: "train", id: "RED-9" }} network={network} snapshot={snapshot()}
                         onClose={() => {}} />);
    expect(screen.getByText(/Not in service/)).toBeTruthy();
  });

  it("closes from the button and with Escape", () => {
    const onClose = vi.fn();
    render(<DetailDrawer selection={{ kind: "train", id: "RED-9" }} network={network} snapshot={null} onClose={onClose} />);

    fireEvent.click(screen.getByRole("button", { name: "Close details" }));
    fireEvent.keyDown(window, { key: "Escape" });
    fireEvent.keyDown(window, { key: "Enter" });
    expect(onClose).toHaveBeenCalledTimes(2);
  });
});
