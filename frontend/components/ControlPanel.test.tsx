import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ControlPanel } from "@/components/ControlPanel";
import { api } from "@/lib/api";
import type { DelayImpactReport, IncidentImpactReport, StrategyOutcome } from "@/lib/types";
import { network, snapshot, trainView } from "@/test/fixtures";

function outcome(strategy: string): StrategyOutcome {
  return {
    strategy, trainsAffected: 1, maxTrainDelaySeconds: 95, totalExtraPassengerWaitMinutes: 40,
    totalPassengerWaitMinutes: 900, totalExtraRideMinutes: 12, totalExtraTravelMinutes: 52,
    totalPassengerTravelMinutes: 3000, headwayCvAtHorizon: 0.1, trunkHeadwayCvAtHorizon: {},
    headwayRecoverySeconds: 600, peakHeadwayCvExcess: 0.2, shortTurns: [], skippedStops: [], trains: [], stations: [],
  };
}

describe("ControlPanel door faults", () => {
  const live = snapshot({ trains: [trainView("RED-1"), trainView("RED-2")] });

  beforeEach(() => {
    vi.spyOn(api, "strategies").mockResolvedValue(["NO_CONTROL", "BALANCED"]);
  });
  afterEach(() => vi.restoreAllMocks());

  it("holds the chosen train with a door fault for the given seconds", async () => {
    const doorFault = vi.spyOn(api, "doorFault").mockResolvedValue(undefined);
    render(<ControlPanel snapshot={live} network={network} />);

    fireEvent.change(screen.getByLabelText("Train"), { target: { value: "RED-2" } });
    fireEvent.change(screen.getByLabelText("Seconds"), { target: { value: "90" } });
    fireEvent.click(screen.getByRole("button", { name: "Door fault" }));

    await waitFor(() => expect(doorFault).toHaveBeenCalledWith("RED-2", 90));
  });

  it("compares strategies on a door fault without injecting it", async () => {
    const report: IncidentImpactReport = {
      incident: { kind: "DOOR_FAULT", trainId: "RED-1", seconds: 240 }, horizonSeconds: 1800,
      outcomes: [outcome("BALANCED"), outcome("NO_CONTROL")], bestStrategy: "BALANCED",
    };
    const analyze = vi.spyOn(api, "analyzeDoorFault").mockResolvedValue(report);
    const doorFault = vi.spyOn(api, "doorFault");
    render(<ControlPanel snapshot={live} network={network} />);

    fireEvent.click(screen.getByRole("button", { name: "What-if door fault" }));

    expect(await screen.findByText("Impact of a 240 s door fault on RED-1")).toBeTruthy();
    expect(analyze).toHaveBeenCalledWith("RED-1", 240);
    expect(doorFault).not.toHaveBeenCalled();
  });
});

describe("ControlPanel what-if requested from elsewhere", () => {
  const live = snapshot({ trains: [trainView("RED-1"), trainView("RED-2")] });

  beforeEach(() => {
    vi.spyOn(api, "strategies").mockResolvedValue(["NO_CONTROL", "BALANCED"]);
  });
  afterEach(() => vi.restoreAllMocks());

  it("runs the requested analysis, selects its train and maps the best strategy", async () => {
    const report: IncidentImpactReport = {
      incident: { kind: "DOOR_FAULT", trainId: "RED-2", seconds: 90 }, horizonSeconds: 1800,
      outcomes: [outcome("BALANCED"), outcome("NO_CONTROL")], bestStrategy: "BALANCED",
    };
    const analyze = vi.spyOn(api, "analyzeDoorFault").mockResolvedValue(report);
    const onHeat = vi.fn();
    const { rerender } = render(<ControlPanel snapshot={live} network={network} onHeat={onHeat} />);
    expect(analyze).not.toHaveBeenCalled();

    rerender(<ControlPanel snapshot={live} network={network} onHeat={onHeat}
                           whatIf={{ id: 1, kind: "DOOR_FAULT", trainId: "RED-2", seconds: 90 }} />);

    expect(await screen.findByText("Impact of a 90 s door fault on RED-2")).toBeTruthy();
    expect(analyze).toHaveBeenCalledWith("RED-2", 90);
    expect((screen.getByLabelText("Train") as HTMLSelectElement).value).toBe("RED-2");
    expect((screen.getByLabelText("Seconds") as HTMLInputElement).value).toBe("90");
    expect(onHeat).toHaveBeenLastCalledWith(expect.objectContaining({ label: "BALANCED" }));
  });

  it("runs a delay what-if again for a new request but not on other re-renders", async () => {
    const report: DelayImpactReport = {
      trainId: "RED-1", injectedDelaySeconds: 120, horizonSeconds: 1800,
      outcomes: [outcome("BALANCED")], bestStrategy: "BALANCED",
    };
    const analyze = vi.spyOn(api, "analyze").mockResolvedValue(report);
    const request = { id: 1, kind: "DELAY" as const, trainId: "RED-1", seconds: 120 };
    const { rerender } = render(<ControlPanel snapshot={live} network={network} whatIf={request} />);
    expect(await screen.findByText("Impact of 2 min delay on RED-1")).toBeTruthy();

    rerender(<ControlPanel snapshot={snapshot({ trains: [trainView("RED-1")] })} network={network} whatIf={request} />);
    rerender(<ControlPanel snapshot={live} network={network} whatIf={{ ...request, id: 2 }} />);

    await waitFor(() => expect(analyze).toHaveBeenCalledTimes(2));
  });
});

describe("ControlPanel power failures", () => {
  const live = snapshot({ trains: [trainView("RED-1")] });

  beforeEach(() => {
    vi.spyOn(api, "strategies").mockResolvedValue(["NO_CONTROL", "BALANCED"]);
  });
  afterEach(() => vi.restoreAllMocks());

  it("cuts power to the chosen line for the closure minutes", async () => {
    const cutPower = vi.spyOn(api, "cutPower").mockResolvedValue(undefined);
    render(<ControlPanel snapshot={live} network={network} />);

    fireEvent.change(screen.getByLabelText("Line"), { target: { value: "BLUE" } });
    fireEvent.change(screen.getByLabelText("Minutes"), { target: { value: "5" } });
    fireEvent.click(screen.getByRole("button", { name: "Cut power" }));

    await waitFor(() => expect(cutPower).toHaveBeenCalledWith("BLUE", 300));
  });

  it("compares strategies on a power failure without cutting power", async () => {
    const report: IncidentImpactReport = {
      incident: { kind: "POWER_FAILURE", lineId: "RED", seconds: 600 }, horizonSeconds: 1800,
      outcomes: [outcome("BALANCED"), outcome("NO_CONTROL")], bestStrategy: "BALANCED",
    };
    const analyze = vi.spyOn(api, "analyzePowerFailure").mockResolvedValue(report);
    const cutPower = vi.spyOn(api, "cutPower");
    render(<ControlPanel snapshot={live} network={network} />);

    // The power row's what-if is the last of the closure panel's.
    const whatIfs = screen.getAllByRole("button", { name: "What-if" });
    fireEvent.click(whatIfs[whatIfs.length - 1]);

    expect(await screen.findByText("Impact of a 10 min power failure on Red Line")).toBeTruthy();
    expect(analyze).toHaveBeenCalledWith("RED", 600);
    expect(cutPower).not.toHaveBeenCalled();
  });
});
