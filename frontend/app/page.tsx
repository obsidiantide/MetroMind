"use client";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { ControlPanel } from "@/components/ControlPanel";
import { DetailDrawer, type Selection } from "@/components/DetailDrawer";
import { IncidentTimeline } from "@/components/IncidentTimeline";
import { KpiCharts } from "@/components/KpiCharts";
import { MetroMap, type WaitHeat } from "@/components/MetroMap";
import { api } from "@/lib/api";
import type { MetroNetwork } from "@/lib/types";
import { useSimulation } from "@/lib/useSimulation";

export default function Home() {
  const [network, setNetwork] = useState<MetroNetwork | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { snapshot, connected } = useSimulation();
  const [selection, setSelection] = useState<Selection | null>(null);
  const closeDrawer = useCallback(() => setSelection(null), []);
  const [heat, setHeat] = useState<WaitHeat | null>(null);

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
            {snapshot?.clock ?? "--:--:--"} · {connected ? "live" : "connecting…"} · <Link href="/benchmark">Benchmark</Link>
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
            {m.stopsSkipped > 0 && (
              <div className="kpi">
                <b>{m.stopsSkipped}</b>
                <span>stops skipped · {Math.round(m.passengersCarriedPast)} carried past</span>
              </div>
            )}
            <div className="kpi">
              <b>{m.trainsInService}</b>
              <span>trains in service{m.trainsInDepot > 0 ? ` · ${m.trainsInDepot} in depot` : ""}</span>
            </div>
          </section>
        )}
        {snapshot && <IncidentTimeline snapshot={snapshot} />}
        <KpiCharts />
        <section className="panel">
          {error && <p style={{ color: "var(--bad)" }}>Backend unreachable: {error}</p>}
          {network && (
            <MetroMap network={network} snapshot={snapshot} selected={selection} onSelect={setSelection} heat={heat} />
          )}
        </section>
      </div>
      <ControlPanel snapshot={snapshot} network={network} onHeat={setHeat} />
      {selection && network && (
        <DetailDrawer selection={selection} network={network} snapshot={snapshot} onClose={closeDrawer} />
      )}
    </main>
  );
}
