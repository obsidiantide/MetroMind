"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { DelayImpactReport, Snapshot } from "@/lib/types";

export function ControlPanel({ snapshot }: { snapshot: Snapshot | null }) {
  const [strategies, setStrategies] = useState<string[]>([]);
  const [trainId, setTrainId] = useState("");
  const [seconds, setSeconds] = useState(240);
  const [report, setReport] = useState<DelayImpactReport | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.strategies().then(setStrategies).catch((e) => setError(String(e)));
  }, []);
  useEffect(() => {
    if (!trainId && snapshot?.trains.length) setTrainId(snapshot.trains[0].id);
  }, [snapshot, trainId]);

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

      {report && (
        <section className="panel">
          <h2>Impact of {Math.round(report.injectedDelaySeconds / 60)} min delay on {report.trainId}</h2>
          <table>
            <thead>
              <tr><th>Strategy</th><th>Trains hit</th><th>Max delay</th><th>Extra wait</th><th>Total wait</th></tr>
            </thead>
            <tbody>
              {report.outcomes.map((o) => (
                <tr key={o.strategy} className={o.strategy === report.bestStrategy ? "best" : ""}>
                  <td>{o.strategy}</td>
                  <td>{o.trainsAffected}</td>
                  <td>{Math.round(o.maxTrainDelaySeconds)} s</td>
                  <td>{Math.round(o.totalExtraPassengerWaitMinutes)} min</td>
                  <td>{Math.round(o.totalPassengerWaitMinutes)} min</td>
                </tr>
              ))}
            </tbody>
          </table>
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
