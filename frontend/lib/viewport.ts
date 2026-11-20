/**
 * Pan and zoom of the network map: the map content is scaled by `scale` and shifted by (`tx`, `ty`) in SVG units,
 * so a point p of the content is drawn at p · scale + t. The view never zooms out beyond the whole network nor pans
 * past its edges, so the map always fills its frame.
 */
export interface Viewport {
  scale: number;
  tx: number;
  ty: number;
}

export const WHOLE_NETWORK: Viewport = { scale: 1, tx: 0, ty: 0 };
export const MAX_SCALE = 8;

/** Keeps `v` within the zoom limits and the content covering a frame of `width` × `height`. */
export function clampViewport(v: Viewport, width: number, height: number): Viewport {
  const scale = Math.min(MAX_SCALE, Math.max(1, v.scale));
  const clamp = (t: number, size: number) => Math.min(0, Math.max(size - size * scale, t));
  return { scale, tx: clamp(v.tx, width), ty: clamp(v.ty, height) };
}

/** Zooms by `factor` about the frame point (`px`, `py`), which stays where it is on screen. */
export function zoomAt(v: Viewport, factor: number, px: number, py: number, width: number, height: number): Viewport {
  const scale = Math.min(MAX_SCALE, Math.max(1, v.scale * factor));
  const [cx, cy] = [(px - v.tx) / v.scale, (py - v.ty) / v.scale];
  return clampViewport({ scale, tx: px - cx * scale, ty: py - cy * scale }, width, height);
}

/** Moves the content by (`dx`, `dy`) frame units. */
export function panBy(v: Viewport, dx: number, dy: number, width: number, height: number): Viewport {
  return clampViewport({ ...v, tx: v.tx + dx, ty: v.ty + dy }, width, height);
}

/** The SVG transform that applies `v` to the map content. */
export function viewportTransform(v: Viewport): string {
  return `translate(${round(v.tx)},${round(v.ty)}) scale(${round(v.scale)})`;
}

function round(n: number) {
  return Math.round(n * 1000) / 1000;
}
