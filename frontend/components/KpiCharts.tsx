"use client";
import { useEffect, useState } from "react";
import { LineChart } from "@/components/LineChart";
import { api } from "@/lib/api";
import type { MinuteKpi } from "@/lib/types";

/** At the default speed (10x) a simulated minute closes every 6 s, so this keeps the charts at most a minute behind. */
const REFRESH_MS = 6000;
const MINUTES = 60;

/** Delay and passengers waiting per simulated minute over the last hour, from the aggregated KPIs. */
export function KpiCharts() {
  const [kpis, setKpis] = useState<MinuteKpi[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      api.kpis(MINUTES)
        .then((k) => { if (!cancelled) { setKpis(k); setError(null); } })
        .catch((e) => { if (!cancelled) setError(String(e)); });
    void load();
    const timer = setInterval(load, REFRESH_MS);
    return () => { cancelled = true; clearInterval(timer); };
  }, []);

  const labels = kpis.map((k) => k.clock);
  return (
    <section className="panel" aria-label="Delay and waiting over time">
      <h2>Last {MINUTES} minutes</h2>
      {error && <p className="muted">KPIs unavailable: {error}</p>}
      {!error && kpis.length === 0 && <p className="muted">Collecting the first simulated minute…</p>}
      {kpis.length > 0 && (
        <div className="charts">
          <LineChart title="Train delay" unit="s" labels={labels} series={[
            { label: "average", color: "var(--warn)", values: kpis.map((k) => k.averageDelaySeconds) },
            { label: "worst", color: "var(--bad)", values: kpis.map((k) => k.maxDelaySeconds), dashed: true },
          ]} />
          <LineChart title="Passengers waiting" unit="" labels={labels} series={[
            { label: "average", color: "#58a6ff", values: kpis.map((k) => k.averageWaiting) },
            { label: "peak", color: "#a371f7", values: kpis.map((k) => k.maxWaiting), dashed: true },
          ]} />
        </div>
      )}
    </section>
  );
}
