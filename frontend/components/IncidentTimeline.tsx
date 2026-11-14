"use client";
import type { ActiveIncidentView, IncidentKind, Snapshot } from "@/lib/types";

const KIND_COLOR: Record<IncidentKind, string> = {
  TRAIN_DELAY: "var(--warn)",
  BREAKDOWN: "var(--bad)",
  SECTION_CLOSURE: "#a371f7",
  STATION_CLOSURE: "#58a6ff",
};
const KIND_LABEL: Record<IncidentKind, string> = {
  TRAIN_DELAY: "Delay",
  BREAKDOWN: "Breakdown",
  SECTION_CLOSURE: "Track closed",
  STATION_CLOSURE: "Station closed",
};

/** Formats a time of day in simulated seconds since midnight as HH:MM. */
function clock(simTime: number) {
  const s = Math.floor(simTime) % 86400;
  return `${String(Math.floor(s / 3600)).padStart(2, "0")}:${String(Math.floor(s / 60) % 60).padStart(2, "0")}`;
}

function duration(seconds: number) {
  const s = Math.max(0, Math.ceil(seconds));
  return s >= 60 ? `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, "0")}s` : `${s}s`;
}

/**
 * Gantt-style view of the incidents in force: each bar runs from when the incident began to when it clears on its
 * own, on a shared time axis that starts at the oldest incident and ends at the last clearing time.
 */
export function IncidentTimeline({ snapshot }: { snapshot: Snapshot }) {
  const incidents: ActiveIncidentView[] = snapshot.activeIncidents ?? [];
  if (incidents.length === 0) return null;
  const now = snapshot.simTime;
  const from = Math.min(...incidents.map((i) => i.startedAt));
  const to = Math.max(...incidents.map((i) => i.clearsAt));
  const span = Math.max(1, to - from);
  const pct = (t: number) => `${((t - from) / span) * 100}%`;
  const width = (a: number, b: number) => `${(Math.max(0, b - a) / span) * 100}%`;

  return (
    <section className="panel" aria-label="Incident clearing timeline">
      <h2>Incidents clearing</h2>
      <div className="timeline">
        {incidents.map((i) => (
          <div key={`${i.kind}-${i.subjectId}-${i.startedAt}`} className="timeline-row">
            <span title={i.description}>
              <b style={{ color: KIND_COLOR[i.kind] }}>{KIND_LABEL[i.kind]}</b> {i.description}
            </span>
            <div className="timeline-track">
              <div className="timeline-bar timeline-elapsed"
                   style={{ left: pct(i.startedAt), width: width(i.startedAt, now), background: KIND_COLOR[i.kind] }} />
              <div className="timeline-bar"
                   style={{ left: pct(now), width: width(now, i.clearsAt), background: KIND_COLOR[i.kind] }} />
            </div>
            <span className="muted" title={`Clears at ${clock(i.clearsAt)}`}>{duration(i.remainingSeconds)}</span>
          </div>
        ))}
      </div>
      <div className="timeline-axis muted">
        <span>{clock(from)}</span>
        <span>now {clock(now)}</span>
        <span>{clock(to)}</span>
      </div>
    </section>
  );
}
