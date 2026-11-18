import { describe, expect, it } from "vitest";
import { clock, duration, lateness, trunkCv } from "@/lib/format";

describe("format", () => {
  it("shows simulated time of day, wrapping past midnight", () => {
    expect(clock(8 * 3600 + 5 * 60 + 59.9)).toBe("08:05");
    expect(clock(86400 + 60)).toBe("00:01");
  });

  it("rounds durations up to the second", () => {
    expect(duration(40.2)).toBe("41s");
    expect(duration(125)).toBe("2m 05s");
    expect(duration(-3)).toBe("0s");
  });

  it("lists each branched line's trunk CV by name, or a dash without any trunk", () => {
    const name = (id: string) => ({ M1: "Red" } as Record<string, string>)[id] ?? id;
    expect(trunkCv({ M3: 0.3, M1: 0.123 }, name)).toBe("Red 0.12 · M3 0.30");
    expect(trunkCv({}, name)).toBe("–");
  });

  it("signs lateness and calls half a second on time", () => {
    expect(lateness(80)).toBe("+1m 20s");
    expect(lateness(-15)).toBe("-15s");
    expect(lateness(0.4)).toBe("on time");
  });
});
