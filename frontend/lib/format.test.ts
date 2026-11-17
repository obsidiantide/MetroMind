import { describe, expect, it } from "vitest";
import { clock, duration, lateness } from "@/lib/format";

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

  it("signs lateness and calls half a second on time", () => {
    expect(lateness(80)).toBe("+1m 20s");
    expect(lateness(-15)).toBe("-15s");
    expect(lateness(0.4)).toBe("on time");
  });
});
