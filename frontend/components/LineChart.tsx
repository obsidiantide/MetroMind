"use client";

export interface Series {
  label: string;
  color: string;
  values: number[];
  /** Drawn dashed, for secondary series such as a peak next to its mean. */
  dashed?: boolean;
}

const W = 420;
const H = 160;
const PAD = { top: 10, right: 10, bottom: 22, left: 44 };

/** Rounds up to 1, 2 or 5 times a power of ten so the axis reads in round numbers. */
export function niceCeiling(value: number): number {
  if (!(value > 0)) return 1;
  const p = 10 ** Math.floor(Math.log10(value));
  const m = value / p;
  return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 5 : 10) * p;
}

/**
 * Minimal SVG line chart: shared x labels (one per sample), any number of series on a y axis starting at zero.
 * Hovering a column shows every series' value at that sample.
 */
export function LineChart({ title, labels, series, unit }: {
  title: string; labels: string[]; series: Series[]; unit: string;
}) {
  const n = labels.length;
  const top = niceCeiling(Math.max(0, ...series.flatMap((s) => s.values)));
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  const x = (i: number) => PAD.left + (n <= 1 ? innerW : (i / (n - 1)) * innerW);
  const y = (v: number) => PAD.top + innerH - (v / top) * innerH;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => f * top);
  const xTicks = n === 0 ? [] : [...new Set([0, Math.floor((n - 1) / 2), n - 1])];
  const fmt = (v: number) => (top >= 10 ? Math.round(v).toString() : String(Number(v.toFixed(2))));

  return (
    <figure className="chart">
      <figcaption>
        <span>{title}</span>
        <span className="chart-legend">
          {series.map((s) => (
            <span key={s.label}>
              <i style={{ background: s.color }} /> {s.label}
              {n > 0 && <b> {fmt(s.values[n - 1])} {unit}</b>}
            </span>
          ))}
        </span>
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label={title}>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} stroke="var(--border)" />
            <text x={PAD.left - 6} y={y(t) + 3} textAnchor="end">{fmt(t)}</text>
          </g>
        ))}
        {xTicks.map((i) => (
          <text key={i} x={x(i)} y={H - 6} textAnchor={i === 0 ? "start" : i === n - 1 ? "end" : "middle"}>
            {labels[i]}
          </text>
        ))}
        {series.map((s) => (
          <polyline key={s.label} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round"
                    strokeDasharray={s.dashed ? "4 3" : undefined}
                    points={s.values.map((v, i) => `${x(i)},${y(v)}`).join(" ")} />
        ))}
        {labels.map((label, i) => (
          <rect key={label + i} className="chart-hover" x={x(i) - innerW / Math.max(1, n - 1) / 2} y={PAD.top}
                width={innerW / Math.max(1, n - 1)} height={innerH}>
            <title>{`${label}\n${series.map((s) => `${s.label}: ${fmt(s.values[i])} ${unit}`).join("\n")}`}</title>
          </rect>
        ))}
      </svg>
    </figure>
  );
}
