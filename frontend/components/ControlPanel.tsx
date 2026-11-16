"use client";
import { useEffect, useState } from "react";
import type { WaitHeat } from "@/components/MetroMap";
import { api } from "@/lib/api";
import type { ClosureResponse, MetroNetwork, Snapshot, StrategyOutcome } from "@/lib/types";

/** A what-if result ready to show: what was simulated and how each strategy coped. */
interface WhatIf {
  title: string;
  outcomes: StrategyOutcome[];
  bestStrategy: string;
}

/** What each headway strategy does, shown under the selector. */
const STRATEGY_HELP: Record<string, string> = {
  NO_CONTROL: "Trains leave as soon as dwelling is done; bunching grows freely.",
  FORWARD_HOLDING: "Trains wait at platforms until the gap to the train ahead is close to the target headway.",
  BALANCED: "Trains are held to split the difference between the gaps ahead and behind.",
  ADAPTIVE_DISPATCH:
    "Balanced holding; spare trains leave the depot when a line is forecast to overload and return when demand eases.",
  SHORT_TURN:
    "Balanced holding; a train running 4+ min late with a follower close behind reverses early to recover its timetable.",
  DEMAND_HOLDING:
    "Forward holding against a target that shrinks as demand rises, so peak trains are held less and keep moving.",
  SKIP_STOP:
    "Balanced holding; a late train lagging between its neighbours runs through quiet stops, and its follower serves them.",
  SHORT_TURN_SKIP_STOP:
    "Balanced holding; a train 2–4 min late skips quiet stops, and one 4+ min late calls at every stop so it can short-turn.",
};

/** Simulated seconds since midnight as HH:MM. */
function clock(simTime: number): string {
  const minutes = Math.floor(simTime / 60) % (24 * 60);
  return `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
}

function distinct(values: string[]): string[] {
  return [...new Set(values)];
}

/** The station heat of one strategy's outcome, for the map. */
function heatOf(outcome: StrategyOutcome): WaitHeat {
  return {
    label: outcome.strategy,
    extraWaitMinutes: Object.fromEntries(outcome.stations.map((s) => [s.stationId, s.extraWaitMinutes])),
  };
}

export function ControlPanel({ snapshot, network, onHeat = () => {} }: {
  snapshot: Snapshot | null; network: MetroNetwork | null;
  /** Called with the extra wait per station of the best strategy whenever a what-if result arrives. */
  onHeat?: (heat: WaitHeat | null) => void;
}) {
  const [strategies, setStrategies] = useState<string[]>([]);
  const [trainId, setTrainId] = useState("");
  const [seconds, setSeconds] = useState(240);
  const [report, setReport] = useState<WhatIf | null>(null);
  const [section, setSection] = useState("");
  const [closureMinutes, setClosureMinutes] = useState(10);
  const [closureResponse, setClosureResponse] = useState<ClosureResponse>("TURN_BACK");
  const [closedStation, setClosedStation] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.strategies().then(setStrategies).catch((e) => setError(String(e)));
  }, []);
  useEffect(() => {
    const best = report?.outcomes.find((o) => o.strategy === report.bestStrategy);
    onHeat(best ? heatOf(best) : null);
  }, [report, onHeat]);
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
  const selectedSection = () => {
    const [lineId, from, to] = (section || sections[0]?.key || "").split("|");
    return { lineId, from, to };
  };
  const closeSection = () => {
    const { lineId, from, to } = selectedSection();
    return api.closeSection(lineId, from, to, closureMinutes * 60, closureResponse);
  };
  const analyzeDelay = async () => {
    const r = await api.analyze(trainId, seconds);
    setReport({ title: `Impact of ${Math.round(r.injectedDelaySeconds / 60)} min delay on ${r.trainId}`, ...r });
  };
  const analyzeSectionClosure = async () => {
    const { lineId, from, to } = selectedSection();
    const r = await api.analyzeSectionClosure(lineId, from, to, closureMinutes * 60, closureResponse);
    setReport({
      title: `Impact of closing ${stationName.get(from) ?? from} – ${stationName.get(to) ?? to} for ${closureMinutes} min`
        + (closureResponse === "TURN_BACK" ? " (trains turn back)" : " (trains wait)"),
      ...r,
    });
  };
  const analyzeStationClosure = async () => {
    const stationId = closedStation || network!.stations[0].id;
    const r = await api.analyzeStationClosure(stationId, closureMinutes * 60);
    setReport({ title: `Impact of closing ${stationName.get(stationId) ?? stationId} for ${closureMinutes} min`, ...r });
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
        {snapshot && STRATEGY_HELP[snapshot.strategy] && <p className="muted">{STRATEGY_HELP[snapshot.strategy]}</p>}
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
          <button disabled={busy} onClick={() => run(analyzeDelay)}>
            What-if analysis
          </button>
        </div>
        {error && <p style={{ color: "var(--bad)" }}>{error}</p>}
      </section>

      {sections.length > 0 && (
        <section className="panel">
          <h2>Closures</h2>
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
            <button disabled={busy} onClick={() => run(closeSection)}>Close track</button>
            <button disabled={busy} onClick={() => run(analyzeSectionClosure)}>What-if</button>
          </div>
          <div className="row" style={{ marginTop: 8 }}>
            <select value={closedStation || network!.stations[0].id} onChange={(e) => setClosedStation(e.target.value)}
                    aria-label="Station">
              {network!.stations.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
            <button disabled={busy}
                    onClick={() => run(() => api.closeStation(closedStation || network!.stations[0].id, closureMinutes * 60))}>
              Close station
            </button>
            <button disabled={busy} onClick={() => run(analyzeStationClosure)}>What-if</button>
          </div>
          <p className="muted">
            Track: trains wait at the signal or turn back at the last open station. Station: trains run through and
            passengers use the neighbouring stations.
          </p>
        </section>
      )}

      {report && (
        <section className="panel">
          <h2>{report.title}</h2>
          <table>
            <thead>
              <tr>
                <th>Strategy</th><th>Trains hit</th><th>Max delay</th><th>Extra wait</th><th>Extra on board</th>
                <th>Total travel</th><th>Headways recover</th><th>Short turns</th><th>Skipped stops</th>
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
                  <td title={`Peak headway CV +${o.peakHeadwayCvExcess.toFixed(2)} over baseline`}>
                    {o.headwayRecoverySeconds === null
                      ? "not within horizon"
                      : o.headwayRecoverySeconds === 0
                        ? "unaffected"
                        : `${Math.round(o.headwayRecoverySeconds / 60)} min`}
                  </td>
                  <td
                    title={o.shortTurns
                      .map((t) => `${t.trainId} at ${stationName.get(t.stationId) ?? t.stationId}, ${clock(t.simTime)}`)
                      .join("\n")}
                  >
                    {o.shortTurns.length === 0
                      ? "–"
                      : `${o.shortTurns.length} (${distinct(o.shortTurns.map((t) => stationName.get(t.stationId) ?? t.stationId)).join(", ")})`}
                  </td>
                  <td
                    title={o.skippedStops
                      .map((s) => `${s.trainId} through ${stationName.get(s.stationId) ?? s.stationId}, ${clock(s.simTime)}, `
                        + `${Math.round(s.passengersCarriedPast)} carried past`)
                      .join("\n")}
                  >
                    {o.skippedStops.length === 0
                      ? "–"
                      : `${o.skippedStops.length} (${Math.round(o.skippedStops.reduce((sum, s) => sum + s.passengersCarriedPast, 0))} carried past)`}
                  </td>
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
