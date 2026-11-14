"use client";
import { useEffect, useState } from "react";
import { ControlPanel } from "@/components/ControlPanel";
import { MetroMap } from "@/components/MetroMap";
import { api } from "@/lib/api";
import type { MetroNetwork } from "@/lib/types";
import { useSimulation } from "@/lib/useSimulation";

export default function Home() {
  const [network, setNetwork] = useState<MetroNetwork | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { snapshot, connected } = useSimulation();

  useEffect(() => {
    api.network().then(setNetwork).catch((e) => setError(String(e)));
  }, []);

  const m = snapshot?.metrics;
  return (
    <main className="layout">
      <div className="stack">
        <header className="panel row" style={{ justifyContent: "space-between" }}>
          <h1>MetroMind · {network?.city ?? "…"}</h1>
          <span className="muted">
            {snapshot?.clock ?? "--:--:--"} · {connected ? "live" : "connecting…"}
          </span>
        </header>
        {m && (
          <section className="panel kpis">
            <div className="kpi"><b>{Math.round(m.averageDelaySeconds)} s</b><span>avg delay</span></div>
            <div className="kpi"><b>{Math.round(m.totalWaiting)}</b><span>waiting now</span></div>
            <div className="kpi"><b>{Math.round(m.passengersDelivered)}</b><span>journeys completed</span></div>
            <div className="kpi">
              <b>{(m.passengersDelivered > 0 ? m.transfers / m.passengersDelivered : 0).toFixed(2)}</b>
              <span>transfers per journey</span>
            </div>
            <div className="kpi"><b>{Math.round(m.passengersHeldAtEntrance)}</b><span>held at entrances</span></div>
            <div className="kpi"><b>{Math.round(m.passengersLeftBehind)}</b><span>left behind by full trains</span></div>
            <div className="kpi"><b>{m.headwayCoefficientOfVariation.toFixed(2)}</b><span>headway CV (bunching)</span></div>
          </section>
        )}
        <section className="panel">
          {error && <p style={{ color: "var(--bad)" }}>Backend unreachable: {error}</p>}
          {network && <MetroMap network={network} snapshot={snapshot} />}
        </section>
      </div>
      <ControlPanel snapshot={snapshot} />
    </main>
  );
}
