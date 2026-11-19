"use client";
import { useState } from "react";
import { api } from "@/lib/api";
import type { BenchmarkRequest, TimeOfDayReport } from "@/lib/types";

const DEFAULT_TIMES = "08:00, 13:00, 18:00, 22:30";

/** Start times typed as "08:00, 13:00": trimmed, blanks dropped. */
export function parseTimes(text: string): string[] {
  return text.split(",").map((t) => t.trim()).filter((t) => t !== "");
}

/**
 * Ranks the strategies over several times of day with the page's benchmark settings, so a strategy that only shines
 * in the peak does not win on the strength of the current moment.
 */
export function DayBenchmark({ request, disabled }: { request: () => BenchmarkRequest; disabled?: boolean }) {
  const [times, setTimes] = useState(DEFAULT_TIMES);
  const [report, setReport] = useState<TimeOfDayReport | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = async () => {
    setError(null);
    setRunning(true);
    try {
      setReport(await api.benchmarkDay(request(), parseTimes(times)));
    } catch (e) {
      setError(String(e));
    } finally {
      setRunning(false);
    }
  };

  const byWait = (report?.metric ?? "WAIT_PER_BOARDING") === "WAIT_PER_BOARDING";
  return (
    <section className="panel">
      <h2>Across the day</h2>
      <p className="muted">
        The same benchmark from each time of day (up to four), every one from the network warmed up for 30 minutes to
        that time, so the fleet plan&apos;s thin and full periods both count. Runs while you wait.
      </p>
      <div className="row">
        <label>Times of day <input value={times} onChange={(e) => setTimes(e.target.value)} style={{ width: 220 }} />
        </label>
        <button onClick={run} disabled={disabled || running || parseTimes(times).length === 0}>
          {running ? "Ranking…" : "Rank across the day"}
        </button>
      </div>
      {error && <p style={{ color: "var(--bad)" }}>{error}</p>}
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
