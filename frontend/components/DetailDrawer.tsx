"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { lateness } from "@/lib/format";
import type { Departure, MetroNetwork, Snapshot } from "@/lib/types";

export type Selection = { kind: "station"; id: string } | { kind: "train"; id: string };

const RISK_COLOR = { LOW: "var(--ok)", MEDIUM: "var(--warn)", HIGH: "var(--bad)" } as const;
const DEPARTURES_REFRESH_MS = 5000;

/** Side drawer with the live details of the station or train picked on the map. Escape closes it. */
export function DetailDrawer({ selection, network, snapshot, onClose }: {
  selection: Selection; network: MetroNetwork; snapshot: Snapshot | null; onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <aside className="drawer panel" aria-label="Details">
      <button className="drawer-close" onClick={onClose} aria-label="Close details">×</button>
      {selection.kind === "station" && (
        <StationDetail key={selection.id} stationId={selection.id} network={network} snapshot={snapshot} />
      )}
    </aside>
  );
}

function StationDetail({ stationId, network, snapshot }: {
  stationId: string; network: MetroNetwork; snapshot: Snapshot | null;
}) {
  const [departures, setDepartures] = useState<Departure[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      api.departures(stationId)
        .then((d) => { if (!cancelled) { setDepartures(d); setError(null); } })
        .catch((e) => { if (!cancelled) setError(String(e)); });
    void load();
    const timer = setInterval(load, DEPARTURES_REFRESH_MS);
    return () => { cancelled = true; clearInterval(timer); };
  }, [stationId]);

  const station = network.stations.find((s) => s.id === stationId);
  if (!station) return <p className="muted">Unknown station {stationId}</p>;
  const name = (id: string) => network.stations.find((s) => s.id === id)?.name ?? id;
  const line = (id: string) => network.lines.find((l) => l.id === id);
  const lines = network.lines.filter((l) => l.stationIds.includes(stationId));
  const st = snapshot?.stations.find((s) => s.id === stationId);

  return (
    <>
      <h2>Station</h2>
      <h3 className="drawer-title">{station.name}</h3>
      <div className="row drawer-lines">
        {lines.map((l) => <span key={l.id} className="chip" style={{ borderColor: l.color }}>{l.name}</span>)}
        {st?.closed && <span className="chip" style={{ borderColor: "var(--bad)" }}>closed</span>}
      </div>
      {st && (
        <dl className="facts">
          <dt>Waiting now</dt><dd>{Math.round(st.waiting)}{station.platformCapacity ? ` / ${station.platformCapacity}` : ""}</dd>
          <dt>In 15 min</dt>
          <dd>{Math.round(st.predictedWaiting15m)} <b style={{ color: RISK_COLOR[st.overloadRisk] }}>{st.overloadRisk}</b></dd>
          <dt>Held at entrance</dt><dd>{Math.round(st.heldAtEntrance)}</dd>
          <dt>Left behind so far</dt><dd>{Math.round(st.leftBehind)}</dd>
        </dl>
      )}
      {st && st.queues.length > 0 && (
        <>
          <h2>Platforms</h2>
          <table>
            <tbody>
              {st.queues.map((q) => (
                <tr key={`${q.lineId}-${q.towardsStationId}`}>
                  <td style={{ color: line(q.lineId)?.color }}>{line(q.lineId)?.name ?? q.lineId}</td>
                  <td>→ {name(q.towardsStationId)}</td>
                  <td>{Math.round(q.waiting)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      <h2>Departures</h2>
      {error && <p className="muted">Departures unavailable: {error}</p>}
      {departures?.length === 0 && <p className="muted">No departures in the next 30 minutes.</p>}
      {departures && departures.length > 0 && (
        <table>
          <thead><tr><th>Planned</th><th>Train</th><th>Towards</th><th>Expected</th></tr></thead>
          <tbody>
            {departures.slice(0, 12).map((d) => (
              <tr key={`${d.trainId}-${d.plannedTime}`}>
                <td>{d.plannedClock.slice(0, 5)}</td>
                <td style={{ color: line(d.lineId)?.color }}>{d.trainId}</td>
                <td>{name(d.towardsStationId)}</td>
                <td style={{ color: d.delaySeconds >= 60 ? "var(--warn)" : undefined }}>{lateness(d.delaySeconds)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
