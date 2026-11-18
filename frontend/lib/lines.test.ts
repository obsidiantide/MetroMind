import { describe, expect, it } from "vitest";
import { lineSections, lineStationIds, ringTurn } from "@/lib/lines";
import type { Line, Station } from "@/lib/types";

const red: Line = {
  id: "RED", name: "Red", color: "#e53935", stationIds: ["A", "B", "C"], runSeconds: [100, 100],
  trainCount: 4, trainCapacity: 900, spareTrains: 0,
};
const branched: Line = {
  ...red,
  branches: [{ junctionStationId: "B", stationIds: ["X", "Y"], runSeconds: [90, 80], share: 0.25 }],
};

describe("lineStationIds", () => {
  it("lists the main route, then the branch stations", () => {
    expect(lineStationIds(red)).toEqual(["A", "B", "C"]);
    expect(lineStationIds(branched)).toEqual(["A", "B", "C", "X", "Y"]);
  });
});

describe("lineSections", () => {
  it("walks the main route", () => {
    expect(lineSections(red)).toEqual([["A", "B"], ["B", "C"]]);
  });

  it("closes a ring back to its first station", () => {
    expect(lineSections({ ...red, ring: true, runSeconds: [100, 100, 100] }))
      .toEqual([["A", "B"], ["B", "C"], ["C", "A"]]);
  });

  it("adds each branch from its junction outwards", () => {
    expect(lineSections(branched)).toEqual([["A", "B"], ["B", "C"], ["B", "X"], ["X", "Y"]]);
  });
});

describe("ringTurn", () => {
  // A square of stations: N at the top, then E, S and W going clockwise on a north-up map.
  const stations: Record<string, Station> = {
    N: { id: "N", name: "N", lat: 40.01, lon: 49.8, baseArrivalsPerMinute: 1 },
    E: { id: "E", name: "E", lat: 40.0, lon: 49.81, baseArrivalsPerMinute: 1 },
    S: { id: "S", name: "S", lat: 39.99, lon: 49.8, baseArrivalsPerMinute: 1 },
    W: { id: "W", name: "W", lat: 40.0, lon: 49.79, baseArrivalsPerMinute: 1 },
  };
  const station = (id: string) => stations[id];
  const ring: Line = { ...red, ring: true, stationIds: ["N", "E", "S", "W"], runSeconds: [100, 100, 100, 100] };

  it("tells which way the trains run round a ring", () => {
    expect(ringTurn(ring, station)).toBe("clockwise");
    expect(ringTurn({ ...ring, stationIds: ["N", "W", "S", "E"] }, station)).toBe("anticlockwise");
  });

  it("does not depend on which station the ring starts at", () => {
    expect(ringTurn({ ...ring, stationIds: ["S", "W", "N", "E"] }, station)).toBe("clockwise");
  });

  it("is null for a line that is not a ring", () => {
    expect(ringTurn({ ...ring, ring: false }, station)).toBeNull();
  });
});
