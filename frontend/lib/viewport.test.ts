import { describe, expect, it } from "vitest";
import { MAX_SCALE, WHOLE_NETWORK, clampViewport, panBy, viewportTransform, zoomAt } from "@/lib/viewport";

const [W, H] = [900, 640];

describe("map viewport", () => {
  it("zooms about a point, which stays put on screen", () => {
    const v = zoomAt(WHOLE_NETWORK, 2, 300, 200, W, H);
    expect(v.scale).toBe(2);
    expect(v).toEqual({ scale: 2, tx: -300, ty: -200 });
    // The content point under (300, 200) is still drawn there.
    expect(300 * v.scale + v.tx).toBe(300);
    expect(200 * v.scale + v.ty).toBe(200);
  });

  it("never zooms out beyond the whole network nor in beyond the limit", () => {
    expect(zoomAt(WHOLE_NETWORK, 0.5, 450, 320, W, H)).toEqual(WHOLE_NETWORK);
    let v = WHOLE_NETWORK;
    for (let i = 0; i < 10; i++) v = zoomAt(v, 2, 450, 320, W, H);
    expect(v.scale).toBe(MAX_SCALE);
    expect(zoomAt(v, 1 / 64, 0, 0, W, H)).toEqual(WHOLE_NETWORK);
  });

  it("pans within the edges of the network", () => {
    const zoomed = zoomAt(WHOLE_NETWORK, 2, 0, 0, W, H);
    expect(zoomed).toEqual({ scale: 2, tx: 0, ty: 0 });
    expect(panBy(zoomed, -100, -50, W, H)).toEqual({ scale: 2, tx: -100, ty: -50 });
    // Content twice the frame's size can move at most one frame width or height.
    expect(panBy(zoomed, -5000, -5000, W, H)).toEqual({ scale: 2, tx: -W, ty: -H });
    expect(panBy(zoomed, 300, 300, W, H)).toEqual(zoomed);
    expect(panBy(WHOLE_NETWORK, 40, -40, W, H)).toEqual(WHOLE_NETWORK);
  });

  it("clamps a stored view and writes it as an SVG transform", () => {
    expect(clampViewport({ scale: 20, tx: 10, ty: -1e6 }, W, H)).toEqual({ scale: MAX_SCALE, tx: 0, ty: -H * 7 });
    expect(viewportTransform({ scale: 1.5, tx: -12.34567, ty: 0 })).toBe("translate(-12.346,0) scale(1.5)");
  });
});
