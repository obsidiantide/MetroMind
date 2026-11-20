"use client";
import type { Selection } from "@/components/DetailDrawer";
import { useEffect, useRef, useState } from "react";
import type {
  KeyboardEvent as ReactKeyboardEvent, MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent,
} from "react";
import { boundFor, isBranchTerminal, patternStationIds, plannedTrains, ringTurn } from "@/lib/lines";
import type { Branch, Line, MetroNetwork, Snapshot, Station } from "@/lib/types";
import { WHOLE_NETWORK, panBy, viewportTransform, zoomAt, type Viewport } from "@/lib/viewport";

const W = 900;
const H = 640;
const PAD = 50;
/** Zoom per wheel delta unit (a typical notch of 100 zooms by about 16 %). */
const WHEEL_ZOOM = 0.0015;
const KEY_ZOOM = 1.5;
const KEY_PAN_UNITS = 60;
/** Pointer travel before a press on the map becomes a drag rather than a click. */
const DRAG_THRESHOLD_PX = 4;
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
  const track = network.lines.flatMap((l) => [...(l.path ?? []), ...(l.branches ?? []).flatMap((b) => b.path ?? [])]);
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

/** A branch's track as [lon, lat] pairs from its junction: its alignment when known, otherwise straight segments. */
export function branchPoints(branch: Branch, station: (id: string) => Station): [number, number][] {
  if (branch.path?.length) return branch.path;
  return [branch.junctionStationId, ...branch.stationIds].map((id) => [station(id).lon, station(id).lat]);
}

/**
 * Points at the given fractions (0–1) of a polyline's length, each with the heading of the segment it falls on in
 * degrees clockwise from the x axis, for placing direction arrows along a track.
 */
export function pointsAlong(points: [number, number][], fractions: number[]): { x: number; y: number; angle: number }[] {
  const lengths = points.slice(1).map(([x, y], i) => Math.hypot(x - points[i][0], y - points[i][1]));
  const total = lengths.reduce((a, b) => a + b, 0);
  if (total === 0) return [];
  return fractions.map((f) => {
    let along = f * total;
    let i = 0;
    while (i < lengths.length - 1 && along > lengths[i]) along -= lengths[i++];
    const [[x0, y0], [x1, y1]] = [points[i], points[i + 1]];
    const t = lengths[i] > 0 ? along / lengths[i] : 0;
    return { x: x0 + (x1 - x0) * t, y: y0 + (y1 - y0) * t, angle: (Math.atan2(y1 - y0, x1 - x0) * 180) / Math.PI };
  });
}

const TURN_ARROW = { clockwise: "↻", anticlockwise: "↺" } as const;

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
  const lineById = new Map(network.lines.map((l) => [l.id, l]));
  const lineColor = new Map(network.lines.map((l) => [l.id, l.color]));
  const lineName = new Map(network.lines.map((l) => [l.id, l.name]));
  // With a train's drawer open, the stations of its service pattern stand out and the rest of the map fades.
  const selectedTrain = selected?.kind === "train" ? snapshot?.trains.find((t) => t.id === selected.id) : undefined;
  const selectedLine = selectedTrain && lineById.get(selectedTrain.lineId);
  const route = selectedTrain && selectedLine
    ? { color: selectedLine.color, stations: new Set(patternStationIds(selectedLine, selectedTrain.patternId)) }
    : null;
  const turns = new Map(network.lines.map((l) => [l.id, ringTurn(l, station)]));
  const worstHeat = heat ? Math.max(0, ...Object.values(heat.extraWaitMinutes).map(Math.abs)) : 0;

  // Pan and zoom: wheel zooms about the pointer, dragging pans, and with the map focused + / - zoom, the arrow keys
  // pan and 0 shows the whole network again. Legends stay put; only the network moves.
  const svgRef = useRef<SVGSVGElement>(null);
  const [view, setView] = useState<Viewport>(WHOLE_NETWORK);
  const viewRef = useRef(view);
  viewRef.current = view;
  const drag = useRef<{ x: number; y: number; moved: boolean } | null>(null);
  const suppressClick = useRef(false);
  /** Frame (viewBox) units per CSS pixel; 1 where the map has no layout (tests). */
  const unitsPerPixel = () => {
    const width = svgRef.current?.getBoundingClientRect().width ?? 0;
    return width > 0 ? W / width : 1;
  };
  const toFrame = (clientX: number, clientY: number): [number, number] => {
    const r = svgRef.current?.getBoundingClientRect();
    const k = unitsPerPixel();
    return [(clientX - (r?.left ?? 0)) * k, (clientY - (r?.top ?? 0)) * k];
  };
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;
    const onWheel = (e: WheelEvent) => {
      const r = svg.getBoundingClientRect();
      const k = r.width > 0 ? W / r.width : 1;
      const next = zoomAt(viewRef.current, Math.exp(-e.deltaY * WHEEL_ZOOM), (e.clientX - r.left) * k,
        (e.clientY - r.top) * k, W, H);
      // Only take the wheel from the page when it changes the map, so scrolling past an unzoomed map still works.
      if (next.scale === viewRef.current.scale && next.tx === viewRef.current.tx && next.ty === viewRef.current.ty) return;
      e.preventDefault();
      setView(next);
    };
    svg.addEventListener("wheel", onWheel, { passive: false });
    return () => svg.removeEventListener("wheel", onWheel);
  }, []);
  const onPointerDown = (e: ReactPointerEvent<SVGSVGElement>) => {
    if (e.button !== 0) return;
    drag.current = { x: e.clientX, y: e.clientY, moved: false };
  };
  const onPointerMove = (e: ReactPointerEvent<SVGSVGElement>) => {
    const d = drag.current;
    if (!d) return;
    if (!d.moved && Math.hypot(e.clientX - d.x, e.clientY - d.y) < DRAG_THRESHOLD_PX) return;
    if (!d.moved) e.currentTarget.setPointerCapture?.(e.pointerId);
    d.moved = true;
    const k = unitsPerPixel();
    const [dx, dy] = [(e.clientX - d.x) * k, (e.clientY - d.y) * k];
    setView((v) => panBy(v, dx, dy, W, H));
    d.x = e.clientX;
    d.y = e.clientY;
  };
  const endDrag = () => {
    // A drag ends with a click on whatever is under the pointer; it must not select that station or train.
    suppressClick.current = drag.current?.moved ?? false;
    drag.current = null;
  };
  const onClickCapture = (e: ReactMouseEvent) => {
    if (suppressClick.current) {
      e.stopPropagation();
      suppressClick.current = false;
    }
  };
  const onKeyDown = (e: ReactKeyboardEvent<SVGSVGElement>) => {
    const step = KEY_PAN_UNITS;
    const [cx, cy] = [W / 2, H / 2];
    const next: Record<string, (v: Viewport) => Viewport> = {
      "+": (v) => zoomAt(v, KEY_ZOOM, cx, cy, W, H),
      "=": (v) => zoomAt(v, KEY_ZOOM, cx, cy, W, H),
      "-": (v) => zoomAt(v, 1 / KEY_ZOOM, cx, cy, W, H),
      "0": () => WHOLE_NETWORK,
      ArrowLeft: (v) => panBy(v, step, 0, W, H),
      ArrowRight: (v) => panBy(v, -step, 0, W, H),
      ArrowUp: (v) => panBy(v, 0, step, W, H),
      ArrowDown: (v) => panBy(v, 0, -step, W, H),
    };
    const move = next[e.key];
    if (!move) return;
    e.preventDefault();
    setView(move);
  };
  const resetProps = {
    role: "button",
    tabIndex: 0,
    className: "selectable",
    onClick: () => setView(WHOLE_NETWORK),
    onKeyDown: (e: ReactKeyboardEvent) => {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); e.stopPropagation(); setView(WHOLE_NETWORK); }
    },
  };

  return (
    <svg ref={svgRef} viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="Metro network map"
         tabIndex={0} aria-keyshortcuts="+ - 0 ArrowLeft ArrowRight ArrowUp ArrowDown" onKeyDown={onKeyDown}
         onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={endDrag} onPointerCancel={endDrag}
         onClickCapture={onClickCapture} style={{ touchAction: "none", cursor: view.scale > 1 ? "grab" : undefined }}>
      <g data-viewport transform={viewportTransform(view)}>
        {network.lines.flatMap((line) => [
          { key: line.id, points: trackPoints(line, station) },
          ...(line.branches ?? []).map((b) => ({
            key: `${line.id}/${b.stationIds[b.stationIds.length - 1]}`, points: branchPoints(b, station),
          })),
        ].map(({ key, points }) => (
          <polyline
            key={key}
            data-track={key}
            points={points.map(([lon, lat]) => `${x(lon)},${y(lat)}`).join(" ")}
            fill="none"
            stroke={line.color}
            strokeWidth={6}
            strokeLinecap="round"
            opacity={route && line.id !== selectedLine?.id ? 0.25 : 0.75}
          />
        )))}
        {network.lines.filter((l) => l.ring).map((line) => {
          // One chevron per station-to-station run, 40 % of the way along, pointing the way the trains go. Two rings
          // over the same stations in opposite directions place theirs 40 % from opposite ends, so they do not overlap.
          const n = line.stationIds.length;
          const track = trackPoints(line, station).map(([lon, lat]): [number, number] => [x(lon), y(lat)]);
          return (
            <g key={`dir-${line.id}`} data-ring-arrows={line.id} pointerEvents="none">
              {pointsAlong(track, line.stationIds.map((_, k) => (k + 0.4) / n)).map((p, k) => (
                <path key={k} d="M-5,-5L5,0L-5,5Z" fill="#e6edf3"
                      transform={`translate(${p.x},${p.y}) rotate(${p.angle})`} stroke={line.color} strokeWidth={1.5} />
              ))}
            </g>
          );
        })}
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
          const onRoute = route?.stations.has(s.id);
          return (
            <g key={s.id} {...selectable({ kind: "station", id: s.id }, selected, onSelect)}
               opacity={route && !onRoute ? 0.35 : undefined} data-on-route={onRoute || undefined}>
              {onRoute && (
                <circle cx={x(s.lon)} cy={y(s.lat)} r={r + 5} fill="none" stroke={route!.color} strokeWidth={3}
                        opacity={0.8} pointerEvents="none" />
              )}
              <circle cx={x(s.lon)} cy={y(s.lat)} r={r} fill="#0d1117" stroke={RISK_COLOR[st?.overloadRisk ?? "LOW"]} strokeWidth={3}>
                <title>{`${s.name}: ${Math.round(st?.waiting ?? 0)} waiting${capacity}${held}, ${Math.round(st?.predictedWaiting15m ?? 0)} predicted in 15 min${leftBehind}${queues}${heatNote}`}</title>
              </circle>
              <text x={x(s.lon) + 12} y={y(s.lat) - 10}>{s.name}</text>
            </g>
          );
        })}
        {snapshot?.trains.map((t) => {
          const fill = t.failed ? "#f85149" : t.delaySeconds > 120 ? "#d29922" : "#ffffff";
          const line = lineById.get(t.lineId);
          const towards = network.stations.find((s) => s.id === t.towardsStationId)?.name ?? t.towardsStationId;
          // Trains leaving the main route are labelled on the map; the others only in their tooltip.
          const branchBound = line !== undefined && isBranchTerminal(line, t.towardsStationId);
          return (
            <g key={t.id} transform={`translate(${x(t.lon)},${y(t.lat)})`} style={{ transition: "transform 0.5s linear" }}
               {...selectable({ kind: "train", id: t.id }, selected, onSelect)}>
              <rect x={-7} y={-5} width={14} height={10} rx={3} fill={fill} stroke={lineColor.get(t.lineId)} strokeWidth={2.5}>
                <title>{`${t.id} ${boundFor(line, towards, t.towardsStationId)} · ${t.phase} · delay ${Math.round(t.delaySeconds)} s · load ${Math.round(t.load)}/${t.capacity}`}</title>
              </rect>
              {branchBound && <text x={9} y={14} data-bound-for={t.towardsStationId}>{`to ${towards}`}</text>}
            </g>
          );
        })}
      </g>
      <g transform={`translate(${PAD - 30},16)`} aria-label="Line legend">
        {network.lines.map((l, i) => {
          const turn = turns.get(l.id);
          // Lines with a fleet plan show how many trains run against how many the plan calls for right now, so
          // scheduled runs to and from the depot (and any shortfall) can be followed on the map.
          const fleet = snapshot && l.fleetPlan?.length
            ? { running: snapshot.trains.filter((t) => t.lineId === l.id).length, planned: plannedTrains(l, snapshot.simTime) }
            : null;
          return (
            <g key={l.id} transform={`translate(0,${i * 16})`} data-legend={l.id}>
              <line x1={0} y1={-3} x2={18} y2={-3} stroke={l.color} strokeWidth={5} strokeLinecap="round" />
              <text x={26} y={0}>
                {turn ? `${l.name} ${TURN_ARROW[turn]}` : l.name}
                {fleet && (
                  <tspan data-fleet fill={fleet.running < fleet.planned ? RISK_COLOR.MEDIUM : undefined}>
                    {` · ${fleet.running}/${fleet.planned} trains`}
                  </tspan>
                )}
              </text>
              {turn && <title>{`${l.name}: trains run ${turn} round the ring`}</title>}
              {fleet && !turn && (
                <title>{`${l.name}: ${fleet.running} trains running, ${fleet.planned} planned at ${snapshot!.clock.slice(0, 5)}`}</title>
              )}
            </g>
          );
        })}
      </g>
      {heat && (
        <g transform={`translate(${PAD - 30},${H - 24})`} aria-label="What-if heat legend">
          <circle cx={6} cy={-4} r={6} fill="#f85149" opacity={0.6} />
          <text x={16} y={0}>more waiting</text>
          <circle cx={106} cy={-4} r={6} fill="#58a6ff" opacity={0.6} />
          <text x={116} y={0}>less waiting</text>
          <text x={196} y={0}>{`what-if: ${heat.label}, worst ${Math.round(worstHeat)} passenger-min`}</text>
        </g>
      )}
      {view.scale > 1 && (
        <g transform={`translate(${W - 150},16)`} {...resetProps} aria-label="Reset map view" data-reset-view>
          <rect x={0} y={-12} width={140} height={18} rx={4} fill="var(--panel, #161b22)" stroke="currentColor"
                strokeOpacity={0.3} />
          <text x={8} y={1}>{`${view.scale.toFixed(1)}× · whole network`}</text>
        </g>
      )}
    </svg>
  );
}
