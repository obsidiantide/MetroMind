import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MetroMap, projection } from "@/components/MetroMap";
import type { MetroNetwork } from "@/lib/types";
import { network, snapshot, stationView } from "@/test/fixtures";

describe("projection", () => {
  it("keeps distances to scale at the network's latitude and centres it", () => {
    // A square of 0.02° on each side at 60°N is half as wide as it is tall on the ground.
    const square: MetroNetwork = { ...network, lines: [], stations: [
      { id: "SW", name: "SW", lat: 60.0, lon: 30.0, baseArrivalsPerMinute: 1 },
      { id: "NE", name: "NE", lat: 60.02, lon: 30.02, baseArrivalsPerMinute: 1 },
    ] };
    const { x, y } = projection(square);
    const width = x(30.02) - x(30.0);
    const height = y(60.0) - y(60.02);

    expect(width / height).toBeCloseTo(Math.cos((60.01 * Math.PI) / 180), 6);
    expect((x(30.0) + x(30.02)) / 2).toBeCloseTo(450, 6);
    expect((y(60.0) + y(60.02)) / 2).toBeCloseTo(320, 6);
  });

  it("fits the view to track geometry that bulges beyond the stations", () => {
    const curved: MetroNetwork = { ...network, lines: [{ ...network.lines[0],
      path: [[49.8, 40.0], [49.82, 40.05], [49.84, 40.0]] }] };
    const { y } = projection(curved);
    expect(y(40.05)).toBeGreaterThanOrEqual(50);
    expect(y(40.0)).toBeLessThanOrEqual(640 - 50);
  });
});

describe("MetroMap", () => {
  it("closes a ring drawn from its stations back to the first one", () => {
    const ring: MetroNetwork = { ...network, lines: [{ ...network.lines[0], ring: true, runSeconds: [180, 180, 180] }] };
    const { container } = render(<MetroMap network={ring} snapshot={null} />);
    const points = container.querySelector("polyline")?.getAttribute("points")?.split(" ") ?? [];

    expect(points).toHaveLength(4);
    expect(points[3]).toBe(points[0]);
  });


  it("draws a line along its track when it has one", () => {
    const curved: MetroNetwork = { ...network, lines: [{ ...network.lines[0],
      path: [[49.8, 40.0], [49.81, 40.01], [49.82, 40.0], [49.84, 40.0]] }, network.lines[1]] };
    const { container } = render(<MetroMap network={curved} snapshot={null} />);
    const [red, blue] = [...container.querySelectorAll("polyline")];

    expect(red.getAttribute("points")?.split(" ")).toHaveLength(4);
    expect(blue.getAttribute("points")?.split(" ")).toHaveLength(2);
  });

  it("draws each branch from its junction in the line's colour", () => {
    const branched: MetroNetwork = { ...network, lines: [{ ...network.lines[0], branches: [
      { junctionStationId: "C", stationIds: ["N"], runSeconds: [150], share: 0.5 },
    ] }] };
    const { container } = render(<MetroMap network={branched} snapshot={null} />);
    const branch = container.querySelector('polyline[data-track="RED/N"]')!;
    const [junction, terminal] = branch.getAttribute("points")!.split(" ");
    const main = container.querySelector('polyline[data-track="RED"]')!.getAttribute("points")!.split(" ");

    expect(branch.getAttribute("stroke")).toBe("#e53935");
    expect(junction).toBe(main[1]); // C, where the branch leaves the main route
    expect(terminal).not.toBe(junction);
  });

  it("follows a branch's own track and fits the map to it", () => {
    const branched: MetroNetwork = { ...network, lines: [{ ...network.lines[0], branches: [
      { junctionStationId: "C", stationIds: ["N"], runSeconds: [150], share: 0.5,
        path: [[49.82, 40.0], [49.85, 40.05], [49.82, 40.02]] },
    ] }] };
    const { container } = render(<MetroMap network={branched} snapshot={null} />);
    const points = container.querySelector('polyline[data-track="RED/N"]')!.getAttribute("points")!.split(" ");
    const { y } = projection(branched);

    expect(points).toHaveLength(3);
    expect(y(40.05)).toBeGreaterThanOrEqual(50);
  });

  it("selects a station by click or keyboard and marks the selected one", () => {
    const onSelect = vi.fn();
    render(<MetroMap network={network} snapshot={snapshot({ stations: [stationView("C", { waiting: 120 })] })}
                     selected={{ kind: "station", id: "C" }} onSelect={onSelect} />);
    const buttons = screen.getAllByRole("button");
    const central = buttons.find((b) => b.textContent?.includes("Central"))!;
    const alpha = buttons.find((b) => b.textContent?.includes("Alpha"))!;

    expect(central.getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(alpha);
    fireEvent.keyDown(alpha, { key: "Enter" });
    expect(onSelect).toHaveBeenCalledTimes(2);
    expect(onSelect).toHaveBeenCalledWith({ kind: "station", id: "A" });
  });
});
