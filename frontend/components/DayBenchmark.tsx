"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { dayBenchmarkCsv, dayBenchmarkCsvName } from "@/lib/benchmarkCsv";
import { downloadCsv } from "@/lib/download";
import type { BenchmarkRequest, DayBenchmarkJob } from "@/lib/types";

const DEFAULT_TIMES = "08:00, 13:00, 18:00, 22:30";
const POLL_MS = 1000;

/** Start times typed as "08:00, 13:00": trimmed, blanks dropped. */
export function parseTimes(text: string): string[] {
  return text.split(",").map((t) => t.trim()).filter((t) => t !== "");
}

/**
 * Ranks the strategies over several times of day with the page's benchmark settings, so a strategy that only shines
 * in the peak does not win on the strength of the current moment. Runs as a background job: the panel polls it,
 * shows its progress over all periods and can cancel it.
 */
export function DayBenchmark({ request, disabled }: { request: () => BenchmarkRequest; disabled?: boolean }) {
  const [times, setTimes] = useState(DEFAULT_TIMES);
  const [job, setJob] = useState<DayBenchmarkJob | null>(null);
  const [error, setError] = useState<string | null>(null);

  const running = job !== null && (job.status === "QUEUED" || job.status === "RUNNING");
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => {
      api.dayBenchmarkJob(job.id).then(setJob).catch((e) => setError(String(e)));
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [running, job?.id]);

  const run = async () => {
    setError(null);
    try {
      setJob(await api.submitDayBenchmark(request(), parseTimes(times)));
    } catch (e) {
      setError(String(e));
    }
  };
  const cancel = async () => {
    if (!job) return;
    setError(null);
    try {
      setJob(await api.cancelDayBenchmarkJob(job.id));
    } catch (e) {
      setError(String(e));
    }
  };

  const report = job?.report ?? null;
  const byWait = (report?.metric ?? "WAIT_PER_BOARDING") === "WAIT_PER_BOARDING";
  return (
    <section className="panel">
      <h2>Across the day</h2>
      <p className="muted">
        The same benchmark from each time of day (up to four), every one from the network warmed up for 30 minutes to
        that time, so the fleet plan&apos;s thin and full periods both count. Runs in the background.
      </p>
      <div className="row">
        <label>Times of day <input value={times} onChange={(e) => setTimes(e.target.value)} style={{ width: 220 }} />
        </label>
        <button onClick={run} disabled={disabled || running || parseTimes(times).length === 0}>
          Rank across the day
        </button>
      </div>
      {error && <p style={{ color: "var(--bad)" }}>{error}</p>}
      {job && running && (
        <div style={{ marginTop: 10 }}>
          <div className="meter"><div style={{ width: `${job.progress * 100}%`, background: "var(--ok)" }} /></div>
          <div className="row">
            <p className="muted">
              {job.status === "QUEUED" ? "Queued" : "Running"}: {job.completedRuns} of {job.totalRuns} runs
            </p>
            <button onClick={cancel}>Cancel</button>
          </div>
        </div>
      )}
      {job?.status === "CANCELLED" && (
        <p className="muted">Ranking cancelled after {job.completedRuns} of {job.totalRuns} runs.</p>
      )}
      {job?.status === "FAILED" && <p style={{ color: "var(--bad)" }}>Ranking failed: {job.error}</p>}
      {report && (
        <div className="row" style={{ justifyContent: "flex-end" }}>
          <button onClick={() => downloadCsv(dayBenchmarkCsv(report), dayBenchmarkCsvName(report))}>
            Export CSV
          </button>
        </div>
      )}
      {report && (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>#</th><th>Strategy</th>
                <th>{byWait ? "Mean wait / boarding" : "Mean travel time"}</th>
                {report.periods.map((p) => <th key={p.startClock}>{p.startClock}</th>)}
                <th>Worst at</th>
              </tr>
            </thead>
            <tbody>
              {report.rankings.map((r) => (
                <tr key={r.strategy}>
                  <td>{r.rank}</td>
                  <td>{r.strategy}</td>
                  <td>{byWait ? `${r.meanWaitSeconds.toFixed(1)} s` : `${Math.round(r.meanTravelMinutes)} min`}</td>
                  {report.periods.map((p) => (
                    <td key={p.startClock}>
                      #{p.report.rankings.find((x) => x.strategy === r.strategy)?.rank ?? "–"}
                    </td>
                  ))}
                  <td>{r.worstStartClock} (#{r.worstRank})</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
