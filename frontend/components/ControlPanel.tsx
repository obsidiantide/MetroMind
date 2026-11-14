"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { ClosureResponse, DelayImpactReport, MetroNetwork, Snapshot } from "@/lib/types";

export function ControlPanel({ snapshot, network }: { snapshot: Snapshot | null; network: MetroNetwork | null }) {
  const [strategies, setStrategies] = useState<string[]>([]);
  const [trainId, setTrainId] = useState("");
  const [seconds, setSeconds] = useState(240);
  const [report, setReport] = useState<DelayImpactReport | null>(null);
  const [section, setSection] = useState("");
  const [closureMinutes, setClosureMinutes] = useState(10);
  const [closureResponse, setClosureResponse] = useState<ClosureResponse>("TURN_BACK");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.strategies().then(setStrategies).catch((e) => setError(String(e)));
  }, []);
  useEffect(() => {
    if (!trainId && snapshot?.trains.length) setTrainId(snapshot.trains[0].id);
  }, [snapshot, trainId]);

  const stationName = new Map(network?.stations.map((s) => [s.id, s.name]) ?? []);
  // Every pair of adjacent stations, encoded as "line|from|to".
  const sections = (network?.lines ?? []).flatMap((l) =>
    l.stationIds.slice(1).map((to, i) => ({
      key: `${l.id}|${l.stationIds[i]}|${to}`,
      label: `${l.name}: ${stationName.get(l.stationIds[i])} – ${stationName.get(to)}`,
    })),
  );
  const closeSection = () => {
    const [lineId, from, to] = (section || sections[0]?.key || "").split("|");
    return api.closeSection(lineId, from, to, closureMinutes * 60, closureResponse);
  };

  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="stack">
      <section className="panel">
        <h2>Headway strategy</h2>
        <select value={snapshot?.strategy ?? ""} onChange={(e) => run(() => api.setStrategy(e.target.value))}>
          {strategies.map((s) => <option key={s}>{s}</option>)}
        </select>
      </section>

      <section className="panel">
        <h2>Incident</h2>
        <div className="row">
          <select value={trainId} onChange={(e) => setTrainId(e.target.value)} aria-label="Train">
            {snapshot?.trains.map((t) => <option key={t.id}>{t.id}</option>)}
          </select>
          <input type="number" min={1} max={3600} value={seconds} onChange={(e) => setSeconds(+e.target.value)}
                 aria-label="Seconds" style={{ width: 80 }} /> s
        </div>
        <div className="row" style={{ marginTop: 8 }}>
          <button disabled={busy} onClick={() => run(() => api.delay(trainId, seconds))}>Delay</button>
          <button disabled={busy} onClick={() => run(() => api.breakdown(trainId, seconds))}>Breakdown</button>
          <button disabled={busy} onClick={() => run(async () => setReport(await api.analyze(trainId, seconds)))}>
            What-if analysis
          </button>
        </div>
        {error && <p style={{ color: "var(--bad)" }}>{error}</p>}
      </section>

      {sections.length > 0 && (
        <section className="panel">
          <h2>Track closure</h2>
          <select value={section || sections[0].key} onChange={(e) => setSection(e.target.value)} aria-label="Section">
            {sections.map((s) => <option key={s.key} value={s.key}>{s.label}</option>)}
          </select>
          <div className="row" style={{ marginTop: 8 }}>
            <input type="number" min={1} max={120} value={closureMinutes} onChange={(e) => setClosureMinutes(+e.target.value)}
                   aria-label="Minutes" style={{ width: 70 }} /> min
            <select value={closureResponse} onChange={(e) => setClosureResponse(e.target.value as ClosureResponse)}
                    aria-label="Trains">
              <option value="TURN_BACK">Trains turn back</option>
              <option value="WAIT">Trains wait</option>
            </select>
            <button disabled={busy} onClick={() => run(closeSection)}>Close</button>
          </div>
        </section>
      )}

      {report && (
        <section className="panel">
          <h2>Impact of {Math.round(report.injectedDelaySeconds / 60)} min delay on {report.trainId}</h2>
          <table>
            <thead>
              <tr>
                <th>Strategy</th><th>Trains hit</th><th>Max delay</th><th>Extra wait</th><th>Extra on board</th>
                <th>Total travel</th>
              </tr>
            </thead>
            <tbody>
              {report.outcomes.map((o) => (
                <tr key={o.strategy} className={o.strategy === report.bestStrategy ? "best" : ""}>
                  <td>{o.strategy}</td>
                  <td>{o.trainsAffected}</td>
                  <td>{Math.round(o.maxTrainDelaySeconds)} s</td>
                  <td>{Math.round(o.totalExtraPassengerWaitMinutes)} min</td>
                  <td>{Math.round(o.totalExtraRideMinutes)} min</td>
                  <td>{Math.round(o.totalPassengerTravelMinutes)} min</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="muted">
            Strategies are ranked by total travel time (platform wait + time on board) over the horizon.
          </p>
          <p className="muted">
            Most affected trains ({report.outcomes[0].strategy}):{" "}
            {report.outcomes[0].trains.slice(0, 4).map((t) => `${t.trainId} +${Math.round(t.extraDelaySeconds)}s`).join(", ")}
          </p>
        </section>
      )}

      <section className="panel">
        <h2>Events</h2>
        <div className="events">
          {[...(snapshot?.recentEvents ?? [])].reverse().map((e, i) => (
            <div key={i}><span className="muted">{e.type}</span> {e.message}</div>
          ))}
        </div>
      </section>
    </div>
  );
}
