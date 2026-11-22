"use client";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { DayBenchmark } from "@/components/DayBenchmark";
import { api } from "@/lib/api";
import { benchmarkCsv, benchmarkCsvName } from "@/lib/benchmarkCsv";
import { downloadCsv } from "@/lib/download";
import { duration, trunkCv } from "@/lib/format";
import type {
  BenchmarkJob, BenchmarkReport, BenchmarkRequest, IncidentScenario, MetroNetwork, RankingMetric, ScenarioKind, StrategyRanking,
} from "@/lib/types";

const KINDS: { kind: ScenarioKind; label: string }[] = [
  { kind: "DELAY", label: "Train delays" },
  { kind: "BREAKDOWN", label: "Breakdowns" },
  { kind: "DOOR_FAULT", label: "Door faults" },
  { kind: "SECTION_CLOSURE", label: "Track closures" },
  { kind: "STATION_CLOSURE", label: "Station closures" },
  { kind: "POWER_FAILURE", label: "Power failures" },
];
const POLL_MS = 1000;

/** A spread typed in percent as a fraction; empty means "as on the live engine". */
function percent(text: string): number | undefined {
  return text.trim() === "" ? undefined : Number(text) / 100;
}

/** Station and line names by id, falling back to the id while the network is loading. */
type Names = (id: string | undefined) => string;

function describe(s: IncidentScenario, name: Names): string {
  const length = duration(s.seconds);
  switch (s.kind) {
    case "DELAY": return `${s.trainId} delayed ${length}`;
    case "BREAKDOWN": return `${s.trainId} broken down ${length}`;
    case "SECTION_CLOSURE":
      return `${name(s.lineId)} ${name(s.fromStationId)} – ${name(s.toStationId)} closed ${length}, `
        + (s.response === "TURN_BACK" ? "trains turn back" : "trains wait");
    case "STATION_CLOSURE": return `${name(s.stationId)} closed ${length}`;
    case "DOOR_FAULT": return `${s.trainId} held by a door fault ${length}`;
    case "POWER_FAILURE": return `${name(s.lineId)} without traction power ${length}`;
  }
}

/** Runs strategy benchmarks as background jobs and ranks the headway strategies over random incidents. */
export default function BenchmarkPage() {
  const [scenarios, setScenarios] = useState(10);
  const [seed, setSeed] = useState(1);
  const [horizonMinutes, setHorizonMinutes] = useState(30);
  const [kinds, setKinds] = useState<ScenarioKind[]>(KINDS.map((k) => k.kind));
  const [metric, setMetric] = useState<RankingMetric>("WAIT_PER_BOARDING");
  // Run-time spread in percent; empty uses the live engine's.
  const [spread, setSpread] = useState("");
  // Dwell spread in percent; empty uses the live engine's.
  const [dwellSpread, setDwellSpread] = useState("");
  // Share of late relief drivers in percent; empty uses the live engine's.
  const [lateReliefs, setLateReliefs] = useState("");
  const [job, setJob] = useState<BenchmarkJob | null>(null);
  const [history, setHistory] = useState<BenchmarkJob[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [network, setNetwork] = useState<MetroNetwork | null>(null);

  useEffect(() => { api.network().then(setNetwork).catch(() => {}); }, []);
  const names = new Map([...(network?.stations ?? []), ...(network?.lines ?? [])].map((x) => [x.id, x.name]));
  const name: Names = (id) => (id === undefined ? "?" : names.get(id) ?? id);

  const refreshHistory = useCallback(() => {
    api.benchmarkJobs().then(setHistory).catch((e) => setError(String(e)));
  }, []);
  useEffect(refreshHistory, [refreshHistory]);

  const running = job !== null && (job.status === "QUEUED" || job.status === "RUNNING");
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => {
      api.benchmarkJob(job.id)
        .then((j) => { setJob(j); if (j.status !== "QUEUED" && j.status !== "RUNNING") refreshHistory(); })
        .catch((e) => setError(String(e)));
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [running, job?.id, refreshHistory]);

  const request = (): BenchmarkRequest => ({
    scenarios, seed, horizonSeconds: horizonMinutes * 60, kinds, metric,
    runTimeVariability: percent(spread), dwellVariability: percent(dwellSpread),
    reliefOverrunShare: percent(lateReliefs),
  });

  const submit = async () => {
    setError(null);
    try {
      setJob(await api.submitBenchmark(request()));
      refreshHistory();
    } catch (e) {
      setError(String(e));
    }
  };
  const cancel = async () => {
    if (!job) return;
    setError(null);
    try {
      setJob(await api.cancelBenchmarkJob(job.id));
      refreshHistory();
    } catch (e) {
      setError(String(e));
    }
  };
  const toggle = (k: ScenarioKind) =>
    setKinds((ks) => (ks.includes(k) ? ks.filter((x) => x !== k) : [...ks, k]));

  return (
    <main className="page stack">
      <header className="panel row" style={{ justifyContent: "space-between" }}>
        <h1>Strategy benchmark</h1>
        <Link href="/">← Live dashboard</Link>
      </header>

      <section className="panel">
        <h2>Run</h2>
        <p className="muted">
          Every headway strategy is run against the same random incidents, all started from the live network&apos;s
          current state, and ranked. The same seed and starting state always give the same scenarios, and the same
          everyday variation of run times, dwells and late relief drivers when they are set.
        </p>
        <div className="row">
          <label>Scenarios <input type="number" min={1} max={200} value={scenarios}
                                  onChange={(e) => setScenarios(+e.target.value)} style={{ width: 70 }} /></label>
          <label>Seed <input type="number" value={seed} onChange={(e) => setSeed(+e.target.value)}
                             style={{ width: 80 }} /></label>
          <label>Horizon <input type="number" min={5} max={120} value={horizonMinutes}
                                onChange={(e) => setHorizonMinutes(+e.target.value)} style={{ width: 60 }} /> min</label>
          <label title="How much each run between stations varies around its nominal time, drawn from the seed">
            Run-time spread <input type="number" min={0} max={25} step={1} value={spread} placeholder="live"
                                   onChange={(e) => setSpread(e.target.value)} style={{ width: 60 }} /> %</label>
          <label title="How much each stop's dwell varies around the one its passengers need, drawn from the seed">
            Dwell spread <input type="number" min={0} max={25} step={1} value={dwellSpread} placeholder="live"
                                onChange={(e) => setDwellSpread(e.target.value)} style={{ width: 60 }} /> %</label>
          <label title="Share of crew changes whose relief driver is late, drawn from the seed">
            Late reliefs <input type="number" min={0} max={100} step={5} value={lateReliefs} placeholder="live"
                                onChange={(e) => setLateReliefs(e.target.value)} style={{ width: 60 }} /> %</label>
          <label>Rank by{" "}
            <select value={metric} onChange={(e) => setMetric(e.target.value as RankingMetric)}>
              <option value="WAIT_PER_BOARDING">Platform wait per boarding</option>
              <option value="TRAVEL_TIME">Total travel time</option>
            </select>
          </label>
        </div>
        <div className="row" style={{ marginTop: 8 }}>
          {KINDS.map((k) => (
            <label key={k.kind}>
              <input type="checkbox" checked={kinds.includes(k.kind)} onChange={() => toggle(k.kind)} /> {k.label}
            </label>
          ))}
          <button onClick={submit} disabled={running || kinds.length === 0}>Run benchmark</button>
        </div>
        {error && <p style={{ color: "var(--bad)" }}>{error}</p>}
        {job && running && (
          <div style={{ marginTop: 10 }}>
            <div className="meter"><div style={{ width: `${job.progress * 100}%`, background: "var(--ok)" }} /></div>
            <div className="row">
              <p className="muted">{job.status === "QUEUED" ? "Queued" : "Running"}: {job.completedRuns} of {job.totalRuns} runs</p>
              <button onClick={cancel}>Cancel</button>
            </div>
          </div>
        )}
        {job?.status === "CANCELLED" && (
          <p className="muted">Benchmark cancelled after {job.completedRuns} of {job.totalRuns} runs.</p>
        )}
        {job?.status === "FAILED" && <p style={{ color: "var(--bad)" }}>Benchmark failed: {job.error}</p>}
      </section>

      {job?.report && <Results report={job.report} name={name} />}

      <DayBenchmark request={request} disabled={kinds.length === 0} />

      {history.length > 0 && (
        <section className="panel">
          <h2>Recent runs</h2>
          <table>
            <thead><tr><th>Submitted</th><th>Status</th><th>Runs</th><th>Seed</th><th>Best</th><th /></tr></thead>
            <tbody>
              {history.map((j) => (
                <tr key={j.id}>
                  <td>{new Date(j.submittedAt).toLocaleTimeString()}</td>
                  <td>{j.status}</td>
                  <td>{j.completedRuns}/{j.totalRuns}</td>
                  <td>{j.report?.seed ?? "–"}</td>
                  <td>{j.report?.rankings[0]?.strategy ?? "–"}</td>
                  <td>{j.id !== job?.id && <button onClick={() => setJob(j)}>Show</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </main>
  );
}

function recovery(r: StrategyRanking, scenarios: number) {
  const mean = r.meanRecoverySeconds === null ? "never"
    : r.meanRecoverySeconds === 0 ? "unaffected" : duration(r.meanRecoverySeconds);
  return r.unrecoveredScenarios > 0 ? `${mean} (${r.unrecoveredScenarios}/${scenarios} not recovered)` : mean;
}

function Results({ report, name }: { report: BenchmarkReport; name: Names }) {
  const n = report.scenarios.length;
  const byWait = report.metric === "WAIT_PER_BOARDING";
  const hasTrunk = report.rankings.some((r) => Object.keys(r.meanTrunkHeadwayCv).length > 0);
  return (
    <section className="panel">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h2>
          Ranking · {n} scenarios · seed {report.seed} · {Math.round(report.horizonSeconds / 60)} min horizon · by{" "}
          {byWait ? "platform wait per boarding" : "total travel time"}
          {(report.runTimeVariability ?? 0) > 0 && ` · run times ±${Math.round((report.runTimeVariability ?? 0) * 100)} %`}
          {(report.dwellVariability ?? 0) > 0 && ` · dwells ±${Math.round((report.dwellVariability ?? 0) * 100)} %`}
          {(report.reliefOverrunShare ?? 0) > 0 && ` · late reliefs ${Math.round((report.reliefOverrunShare ?? 0) * 100)} %`
            + ` (~${Math.round(report.reliefOverrunSeconds ?? 0)} s)`}
        </h2>
        <button onClick={() => downloadCsv(benchmarkCsv(report), benchmarkCsvName(report))}>Export CSV</button>
      </div>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>#</th><th>Strategy</th><th>Wait / boarding</th><th>p95</th><th>Travel</th><th>p95</th>
              <th>Headways recover</th>
              {hasTrunk && <th title="Mean headway CV of a branched line's patterns together on its trunk">Trunk CV</th>}
              <th>Short turns</th><th>Skipped stops</th>
            </tr>
          </thead>
          <tbody>
            {report.rankings.map((r) => (
              <tr key={r.strategy} className={r.rank === 1 ? "best" : ""}>
                <td>{r.rank}</td>
                <td>{r.strategy}</td>
                <td className={byWait ? "sorted" : ""}>{r.meanWaitSeconds.toFixed(1)} s</td>
                <td>{r.p95WaitSeconds.toFixed(1)} s</td>
                <td className={byWait ? "" : "sorted"}>{Math.round(r.meanTravelMinutes)} min</td>
                <td>{Math.round(r.p95TravelMinutes)} min</td>
                <td>{recovery(r, n)}</td>
                {hasTrunk && <td>{trunkCv(r.meanTrunkHeadwayCv, name)}</td>}
                <td>{r.meanShortTurns > 0 ? r.meanShortTurns.toFixed(1) : "–"}</td>
                <td>
                  {r.meanStopsSkipped > 0
                    ? `${r.meanStopsSkipped.toFixed(1)} (${Math.round(r.meanPassengersCarriedPast)} carried past)`
                    : "–"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted">Short turns and skipped stops are per scenario, on average.</p>
      <h2>Scenarios</h2>
      <ol className="scenarios">
        {report.scenarios.map((s, i) => <li key={i}>{describe(s, name)}</li>)}
      </ol>
    </section>
  );
}
