import {W, H, drawScene} from './scene';
import type {SceneObject} from './scene';
import {toGray, boxBlur, sobel} from './raster';

export type StageId = 'raw' | 'gray' | 'blur' | 'edges' | 'contours' | 'detect';

export function createStageRenderer(bctx: CanvasRenderingContext2D) {
  // Scratch space, allocated once. Re-allocating per frame is what turns a smooth canvas
  // animation into a garbage-collection stutter.
  const gray = new Uint8Array(W * H);
  const blurred = new Uint8Array(W * H);
  const tmp = new Uint8Array(W * H);
  const edges = new Uint8Array(W * H);
  const outImage = bctx.createImageData(W, H);

  /** Writes one of the greyscale scratch buffers into `outImage` as RGB. */
  const grayToImage = (g: Uint8Array, tint: [number, number, number] | null) => {
    const d = outImage.data;
    for (let i = 0, p = 0; i < g.length; i++, p += 4) {
      const v = g[i];
      if (tint) {
        d[p] = (v * tint[0]) / 255;
        d[p + 1] = (v * tint[1]) / 255;
        d[p + 2] = (v * tint[2]) / 255;
      } else {
        d[p] = v;
        d[p + 1] = v;
        d[p + 2] = v;
      }
      d[p + 3] = 255;
    }
  };

  /**
   * Renders one stage into the offscreen buffer.
   *
   * `t` is wall-clock seconds and drives the scene's own motion; `progress` is how far this stage
   * has run (0 on entry, 1 by the end of its dwell) and drives the stage's reveal — the detection
   * brackets grow with it, so the boxes visibly acquire their targets instead of snapping on.
   */
  const renderStage = (stage: StageId, t: number, progress: number): {objects: SceneObject[]} => {
    const objects = drawScene(bctx, t);

    if (stage === 'raw') {
      return {objects};
    }

    const src = bctx.getImageData(0, 0, W, H).data;
    toGray(src, gray);

    if (stage === 'gray') {
      grayToImage(gray, null);
      bctx.putImageData(outImage, 0, 0);
      return {objects};
    }

    boxBlur(gray, blurred, tmp, 2);

    if (stage === 'blur') {
      grayToImage(blurred, null);
      bctx.putImageData(outImage, 0, 0);
      return {objects};
    }

    sobel(blurred, edges, 90);

    if (stage === 'edges') {
      // Edges rendered as cyan-on-near-black: a binary Mat has no colour, so tinting it is how
      // you *display* one, and it matches the accent the rest of the page uses for machine output.
      const d = outImage.data;
      for (let i = 0, p = 0; i < edges.length; i++, p += 4) {
        const on = edges[i] > 0;
        d[p] = on ? 126 : 8;
        d[p + 1] = on ? 233 : 12;
        d[p + 2] = on ? 245 : 22;
        d[p + 3] = 255;
      }
      bctx.putImageData(outImage, 0, 0);
      return {objects};
    }

    if (stage === 'contours') {
      // Dim the edge map, then stroke the ground-truth outlines over it — what `findContours`
      // hands back is vector data, and drawing it as vectors is the honest depiction.
      const d = outImage.data;
      for (let i = 0, p = 0; i < edges.length; i++, p += 4) {
        const on = edges[i] > 0;
        d[p] = on ? 40 : 8;
        d[p + 1] = on ? 62 : 12;
        d[p + 2] = on ? 74 : 22;
        d[p + 3] = 255;
      }
      bctx.putImageData(outImage, 0, 0);

      bctx.strokeStyle = '#7ee9f5';
      bctx.lineWidth = 1.6;
      bctx.setLineDash([5, 3]);
      bctx.lineDashOffset = -t * 14;
      for (const o of objects) {
        bctx.strokeRect(o.x, o.y, o.w, o.h);
      }
      bctx.setLineDash([]);
      return {objects};
    }

    // 'detect' — the colour frame, dimmed, with HUD boxes locking on.
    drawScene(bctx, t);
    bctx.fillStyle = 'rgba(5,7,13,0.42)';
    bctx.fillRect(0, 0, W, H);

    bctx.lineWidth = 1.5;
    bctx.font = '600 9px ui-monospace, SFMono-Regular, Menlo, monospace';
    objects.forEach((o, idx) => {
      // Each box locks on in turn: object 0 starts immediately, each later one is delayed by a
      // slice of the stage, so the four targets are acquired in sequence rather than together.
      const delay = idx * 0.12;
      const lock = Math.min(1, Math.max(0, (progress - delay) / 0.3));
      if (lock <= 0) return;
      const hue = ['#7ee9f5', '#ffb454', '#b39bff', '#a9e05a'][idx % 4];
      bctx.strokeStyle = hue;
      const bl = Math.min(o.w, o.h) * 0.32 * lock;
      // Four corner brackets rather than a full rectangle — the standard tracker affordance.
      const corners: Array<[number, number, number, number]> = [
        [o.x, o.y, 1, 1],
        [o.x + o.w, o.y, -1, 1],
        [o.x, o.y + o.h, 1, -1],
        [o.x + o.w, o.y + o.h, -1, -1],
      ];
      for (const [px, py, sx, sy] of corners) {
        bctx.beginPath();
        bctx.moveTo(px + sx * bl, py);
        bctx.lineTo(px, py);
        bctx.lineTo(px, py + sy * bl);
        bctx.stroke();
      }
      // The label only appears once the target is fully acquired, so a half-drawn bracket never
      // carries a confidence number that has not settled.
      if (lock < 1) return;
      const tag = `${o.label} ${o.confidence.toFixed(2)}`;
      const tw = bctx.measureText(tag).width + 6;
      bctx.fillStyle = hue;
      bctx.fillRect(o.x, o.y - 11, tw, 11);
      bctx.fillStyle = '#05070d';
      bctx.fillText(tag, o.x + 3, o.y - 3);
    });

    return {objects};
  };
  return renderStage;
}
