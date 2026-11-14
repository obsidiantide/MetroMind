"use client";
import type { MetroNetwork, Snapshot } from "@/lib/types";

const W = 900;
const H = 640;
const PAD = 50;
const RISK_COLOR = { LOW: "#3fb950", MEDIUM: "#d29922", HIGH: "#f85149" } as const;

export function MetroMap({ network, snapshot }: { network: MetroNetwork; snapshot: Snapshot | null }) {
  const lats = network.stations.map((s) => s.lat);
  const lons = network.stations.map((s) => s.lon);
  const [minLat, maxLat, minLon, maxLon] = [Math.min(...lats), Math.max(...lats), Math.min(...lons), Math.max(...lons)];
  const scale = Math.min((W - 2 * PAD) / (maxLon - minLon || 1), (H - 2 * PAD) / (maxLat - minLat || 1));
  const x = (lon: number) => PAD + (lon - minLon) * scale;
  const y = (lat: number) => H - PAD - (lat - minLat) * scale;
  const station = (id: string) => network.stations.find((s) => s.id === id)!;
  const stationState = new Map(snapshot?.stations.map((s) => [s.id, s]) ?? []);
  const lineColor = new Map(network.lines.map((l) => [l.id, l.color]));
  const lineName = new Map(network.lines.map((l) => [l.id, l.name]));

  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="Metro network map">
      {network.lines.map((line) => (
        <polyline
          key={line.id}
          points={line.stationIds.map((id) => `${x(station(id).lon)},${y(station(id).lat)}`).join(" ")}
          fill="none"
          stroke={line.color}
          strokeWidth={6}
          strokeLinecap="round"
          opacity={0.75}
        />
      ))}
      {snapshot?.closedSections.map((c) => {
        const [a, b] = [station(c.fromStationId), station(c.toStationId)];
        const action = c.response === "TURN_BACK" ? "trains turn back" : "trains wait";
        return (
          <line
            key={`${c.lineId}-${c.fromStationId}`}
            x1={x(a.lon)} y1={y(a.lat)} x2={x(b.lon)} y2={y(b.lat)}
            stroke="#f85149" strokeWidth={10} strokeDasharray="6 6" strokeLinecap="butt"
          >
            <title>{`${lineName.get(c.lineId) ?? c.lineId} closed ${a.name}–${b.name}, ${action}, reopens in ${Math.ceil(c.remainingSeconds / 60)} min`}</title>
          </line>
        );
      })}
      {network.stations.map((s) => {
        const st = stationState.get(s.id);
        const r = 6 + Math.min(14, (st?.waiting ?? 0) / 40);
        const held = st && st.heldAtEntrance >= 1 ? `, ${Math.round(st.heldAtEntrance)} held at entrance` : "";
        const capacity = s.platformCapacity ? ` (platforms hold ${s.platformCapacity})` : "";
        const leftBehind = st && st.leftBehind >= 1 ? `\n  left behind by full trains so far: ${Math.round(st.leftBehind)}` : "";
        const queues = (st?.queues ?? [])
          .map((q) => `\n  ${lineName.get(q.lineId) ?? q.lineId} → ${station(q.towardsStationId).name}: ${Math.round(q.waiting)}`)
          .join("");
        return (
          <g key={s.id}>
            <circle cx={x(s.lon)} cy={y(s.lat)} r={r} fill="#0d1117" stroke={RISK_COLOR[st?.overloadRisk ?? "LOW"]} strokeWidth={3}>
              <title>{`${s.name}: ${Math.round(st?.waiting ?? 0)} waiting${capacity}${held}, ${Math.round(st?.predictedWaiting15m ?? 0)} predicted in 15 min${leftBehind}${queues}`}</title>
            </circle>
            <text x={x(s.lon) + 12} y={y(s.lat) - 10}>{s.name}</text>
          </g>
        );
      })}
      {snapshot?.trains.map((t) => {
        const fill = t.failed ? "#f85149" : t.delaySeconds > 120 ? "#d29922" : "#ffffff";
        return (
          <g key={t.id} transform={`translate(${x(t.lon)},${y(t.lat)})`} style={{ transition: "transform 0.5s linear" }}>
            <rect x={-7} y={-5} width={14} height={10} rx={3} fill={fill} stroke={lineColor.get(t.lineId)} strokeWidth={2.5}>
              <title>{`${t.id} · ${t.phase} · delay ${Math.round(t.delaySeconds)} s · load ${Math.round(t.load)}/${t.capacity}`}</title>
            </rect>
          </g>
        );
      })}
    </svg>
  );
}
