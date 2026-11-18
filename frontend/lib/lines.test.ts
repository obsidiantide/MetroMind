import { describe, expect, it } from "vitest";
import { lineSections, lineStationIds } from "@/lib/lines";
import type { Line } from "@/lib/types";

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
