"use client";
import type { Selection } from "@/components/DetailDrawer";
import type { KeyboardEvent as ReactKeyboardEvent } from "react";
import type { Line, MetroNetwork, Snapshot, Station } from "@/lib/types";

const W = 900;
const H = 640;
const PAD = 50;
const RISK_COLOR = { LOW: "#3fb950", MEDIUM: "#d29922", HIGH: "#f85149" } as const;

/** Extra passenger-minutes of waiting per station from one strategy's what-if outcome, drawn as a heat layer. */
export interface WaitHeat {
  label: string;
  extraWaitMinutes: Record<string, number>;
}

/** Halo colour and size for a station's extra wait relative to the worst one: red for more, blue for less. */
function heatStyle(extra: number, worst: number) {
  const share = worst > 0 ? Math.min(1, Math.abs(extra) / worst) : 0;
  return {
    fill: extra >= 0 ? "#f85149" : "#58a6ff",
    opacity: 0.15 + 0.55 * share,
    grow: 4 + 16 * share,
  };
}

/**
 * Maps WGS84 coordinates onto the SVG, to scale and centred on the stations and any track geometry. A degree of longitude is shorter than a degree of
 * latitude by cos(latitude), so longitudes are scaled by it; otherwise a city at 40°N would look 30 % too wide.
 */
export function projection(network: MetroNetwork) {
  const track = network.lines.flatMap((l) => l.path ?? []);
  const lats = [...network.stations.map((s) => s.lat), ...track.map(([, lat]) => lat)];
  const lons = [...network.stations.map((s) => s.lon), ...track.map(([lon]) => lon)];
  const [minLat, maxLat, minLon, maxLon] = [Math.min(...lats), Math.max(...lats), Math.min(...lons), Math.max(...lons)];
  const k = Math.cos((((minLat + maxLat) / 2) * Math.PI) / 180);
  const [spanX, spanY] = [(maxLon - minLon) * k, maxLat - minLat];
  const scale = Math.min((W - 2 * PAD) / (spanX || 1), (H - 2 * PAD) / (spanY || 1));
  const [offX, offY] = [(W - 2 * PAD - spanX * scale) / 2, (H - 2 * PAD - spanY * scale) / 2];
  return {
    x: (lon: number) => PAD + offX + (lon - minLon) * k * scale,
    y: (lat: number) => H - PAD - offY - (lat - minLat) * scale,
  };
}

/**
 * A line's track as [lon, lat] pairs: its alignment when the network has one, otherwise straight segments between
 * its stations, which on a ring come back round to the first station.
 */
export function trackPoints(line: Line, station: (id: string) => Station): [number, number][] {
  if (line.path?.length) return line.path;
  const stops = line.ring ? [...line.stationIds, line.stationIds[0]] : line.stationIds;
  return stops.map((id) => [station(id).lon, station(id).lat]);
}

/** Keyboard and pointer props that make a map element pick `target` when activated. */
function selectable(target: Selection, selected: Selection | null, onSelect: (s: Selection) => void) {
  const isSelected = selected?.kind === target.kind && selected.id === target.id;
  return {
    className: isSelected ? "selectable selected" : "selectable",
    role: "button",
    tabIndex: 0,
    "aria-pressed": isSelected,
    onClick: () => onSelect(target),
    onKeyDown: (e: ReactKeyboardEvent) => {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onSelect(target); }
    },
  };
}

export function MetroMap({ network, snapshot, selected = null, onSelect = () => {}, heat = null }: {
  network: MetroNetwork; snapshot: Snapshot | null; selected?: Selection | null; onSelect?: (s: Selection) => void;
  heat?: WaitHeat | null;
}) {
  const { x, y } = projection(network);
  const station = (id: string) => network.stations.find((s) => s.id === id)!;
  const stationState = new Map(snapshot?.stations.map((s) => [s.id, s]) ?? []);
  const lineColor = new Map(network.lines.map((l) => [l.id, l.color]));
  const lineName = new Map(network.lines.map((l) => [l.id, l.name]));
  const worstHeat = heat ? Math.max(0, ...Object.values(heat.extraWaitMinutes).map(Math.abs)) : 0;

  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="Metro network map">
      {network.lines.map((line) => (
        <polyline
          key={line.id}
          points={trackPoints(line, station).map(([lon, lat]) => `${x(lon)},${y(lat)}`).join(" ")}
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
      {heat && network.stations.map((s) => {
        const extra = heat.extraWaitMinutes[s.id] ?? 0;
        if (Math.abs(extra) < 0.5) return null;
        const h = heatStyle(extra, worstHeat);
        return (
          <circle key={`heat-${s.id}`} cx={x(s.lon)} cy={y(s.lat)} r={6 + h.grow} fill={h.fill} opacity={h.opacity}
                  pointerEvents="none" />
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
        const extra = heat?.extraWaitMinutes[s.id];
        const heatNote = extra === undefined ? "" : `\n  what-if (${heat!.label}): ${extra >= 0 ? "+" : ""}${Math.round(extra)} passenger-min waiting`;
        if (st?.closed) {
          const [cx, cy, d] = [x(s.lon), y(s.lat), 5];
          return (
            <g key={s.id} opacity={0.8} {...selectable({ kind: "station", id: s.id }, selected, onSelect)}>
              <circle cx={cx} cy={cy} r={8} fill="#0d1117" stroke="#8b949e" strokeWidth={3}>
                <title>{`${s.name}: closed, trains run through; passengers use the neighbouring stations`}</title>
              </circle>
              <path d={`M${cx - d},${cy - d}L${cx + d},${cy + d}M${cx + d},${cy - d}L${cx - d},${cy + d}`}
                    stroke="#f85149" strokeWidth={2.5} pointerEvents="none" />
              <text x={cx + 12} y={cy - 10} textDecoration="line-through">{s.name}</text>
            </g>
          );
        }
        return (
          <g key={s.id} {...selectable({ kind: "station", id: s.id }, selected, onSelect)}>
            <circle cx={x(s.lon)} cy={y(s.lat)} r={r} fill="#0d1117" stroke={RISK_COLOR[st?.overloadRisk ?? "LOW"]} strokeWidth={3}>
              <title>{`${s.name}: ${Math.round(st?.waiting ?? 0)} waiting${capacity}${held}, ${Math.round(st?.predictedWaiting15m ?? 0)} predicted in 15 min${leftBehind}${queues}${heatNote}`}</title>
            </circle>
            <text x={x(s.lon) + 12} y={y(s.lat) - 10}>{s.name}</text>
          </g>
        );
      })}
      {snapshot?.trains.map((t) => {
        const fill = t.failed ? "#f85149" : t.delaySeconds > 120 ? "#d29922" : "#ffffff";
        return (
          <g key={t.id} transform={`translate(${x(t.lon)},${y(t.lat)})`} style={{ transition: "transform 0.5s linear" }}
             {...selectable({ kind: "train", id: t.id }, selected, onSelect)}>
            <rect x={-7} y={-5} width={14} height={10} rx={3} fill={fill} stroke={lineColor.get(t.lineId)} strokeWidth={2.5}>
              <title>{`${t.id} · ${t.phase} · delay ${Math.round(t.delaySeconds)} s · load ${Math.round(t.load)}/${t.capacity}`}</title>
            </rect>
          </g>
        );
      })}
      {heat && (
        <g transform={`translate(${PAD - 30},${H - 24})`} aria-label="What-if heat legend">
          <circle cx={6} cy={-4} r={6} fill="#f85149" opacity={0.6} />
          <text x={16} y={0}>more waiting</text>
          <circle cx={106} cy={-4} r={6} fill="#58a6ff" opacity={0.6} />
          <text x={116} y={0}>less waiting</text>
          <text x={196} y={0}>{`what-if: ${heat.label}, worst ${Math.round(worstHeat)} passenger-min`}</text>
        </g>
      )}
    </svg>
  );
}
