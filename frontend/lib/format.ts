/** Formats a time of day in simulated seconds since midnight as HH:MM. */
export function clock(simTime: number) {
  const s = Math.floor(simTime) % 86400;
  return `${String(Math.floor(s / 3600)).padStart(2, "0")}:${String(Math.floor(s / 60) % 60).padStart(2, "0")}`;
}

/** A duration such as "2m 05s" or "40s", rounded up to the second. */
export function duration(seconds: number) {
  const s = Math.max(0, Math.ceil(seconds));
  return s >= 60 ? `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, "0")}s` : `${s}s`;
}

/** Lateness with its sign: "+1m 20s" late, "-15s" early, "on time" within half a second. */
export function lateness(seconds: number) {
  if (Math.abs(seconds) < 0.5) return "on time";
  return (seconds > 0 ? "+" : "-") + duration(Math.abs(seconds));
}
