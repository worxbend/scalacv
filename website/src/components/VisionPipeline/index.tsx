/**
 * VisionPipeline — the landing page's centrepiece.
 *
 * Rather than illustrate what the library does, this runs it. A synthetic scene is drawn to an
 * offscreen canvas, its pixels are read back, and each pipeline stage is computed in real
 * JavaScript on that pixel buffer — the greyscale conversion is a luma dot product, the blur is a
 * separable box kernel, the edges are a Sobel magnitude with hysteresis-free thresholding. These
 * are the same operations `img.gray.blur(2).canny(80, 160)` performs natively, and the caption
 * under the frame shows the scalacv line responsible for the stage currently on screen.
 *
 * Stages advance on a timer and *morph* into one another: a scan line sweeps down the frame with
 * the outgoing stage above it and the incoming stage below, so the transition itself shows the
 * before and after side by side.
 *
 * Constraints this file respects:
 *   - No third-party code. Everything is Canvas 2D and arithmetic.
 *   - Server-side rendering safe: all canvas work happens inside an effect.
 *   - Idle when invisible. An IntersectionObserver and `document.hidden` both stop the loop, so a
 *     backgrounded tab costs nothing.
 *   - `prefers-reduced-motion: reduce` renders one static frame of the final stage and stops.
 */

import React, {useCallback, useEffect, useRef, useState} from 'react';
import styles from './styles.module.css';
import {W, H, drawScene} from './scene';
import type {SceneObject} from './scene';
import {toGray, boxBlur, sobel} from './raster';

/* -------------------------------------------------------------------------- */
/* Stage definitions                                                          */
/* -------------------------------------------------------------------------- */

type StageId = 'raw' | 'gray' | 'blur' | 'edges' | 'contours' | 'detect';

interface Stage {
  id: StageId;
  /** Uppercase readout shown in the HUD. */
  label: string;
  /** The scalacv expression this stage corresponds to. */
  code: string;
  /** The pixel format the equivalent OpenCV Mat would have at this point. */
  format: string;
  /** One line of plain-language explanation, for readers who do not know the term. */
  hint: string;
}

const STAGES: Stage[] = [
  {
    id: 'raw',
    label: 'capture',
    code: 'Image.read("bench.jpg")',
    format: '8UC3 · BGR',
    hint: 'Three colour channels per pixel, in OpenCV’s blue-green-red order.',
  },
  {
    id: 'gray',
    label: 'greyscale',
    code: '.gray',
    format: '8UC1 · GRAY',
    hint: 'One channel of brightness. Most detectors want this, not colour.',
  },
  {
    id: 'blur',
    label: 'blur',
    code: '.blur(2)',
    format: '8UC1 · GRAY',
    hint: 'Averages each pixel with its neighbours so sensor noise stops looking like an edge.',
  },
  {
    id: 'edges',
    label: 'canny',
    code: '.canny(80, 160)',
    format: '8UC1 · BINARY',
    hint: 'Keeps only pixels where brightness changes sharply — the outlines of things.',
  },
  {
    id: 'contours',
    label: 'contours',
    code: '.contours.filter(_.area > 400)',
    format: 'Seq[Contour]',
    hint: 'Joins edge pixels into closed loops you can measure, count, and sort.',
  },
  {
    id: 'detect',
    label: 'detect',
    code: '.markObjects(detector.detect(img))',
    format: 'Seq[Detection]',
    hint: 'Boxes, labels and confidences — plain immutable Scala data, no Mats left open.',
  },
];

/** How long each stage holds, and how long the morph between two stages takes (milliseconds). */
const HOLD_MS = 2100;
const MORPH_MS = 900;
const CYCLE_MS = HOLD_MS + MORPH_MS;

/* -------------------------------------------------------------------------- */
/* The synthetic scene                                                        */
/* -------------------------------------------------------------------------- */

/* -------------------------------------------------------------------------- */
/* Pixel operations — the actual image processing                             */
/* -------------------------------------------------------------------------- */

/* -------------------------------------------------------------------------- */
/* Component                                                                  */
/* -------------------------------------------------------------------------- */

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

export default function VisionPipeline(): React.ReactElement {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);

  /** Mirrors the animation's current stage into React so the caption can re-render. */
  const [stageIndex, setStageIndex] = useState(0);
  /** Set once the effect has run, so the static server-rendered markup can differ. */
  const [live, setLive] = useState(false);
  const [paused, setPaused] = useState(false);
  const pausedRef = useRef(false);

  const togglePaused = useCallback(() => {
    setPaused((p) => {
      pausedRef.current = !p;
      return !p;
    });
  }, []);

  useEffect(() => {
    setLive(true);
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    const wrap = wrapRef.current;
    if (!canvas || !wrap) return undefined;

    const ctx = canvas.getContext('2d', {alpha: false});
    if (!ctx) return undefined;

    // Offscreen buffer at sensor resolution. The visible canvas is a scaled blit of it.
    const buf = document.createElement('canvas');
    buf.width = W;
    buf.height = H;
    const bctx = buf.getContext('2d', {alpha: false, willReadFrequently: true});
    if (!bctx) return undefined;

    // Scratch space, allocated once. Re-allocating per frame is what turns a smooth canvas
    // animation into a garbage-collection stutter.
    const gray = new Uint8Array(W * H);
    const blurred = new Uint8Array(W * H);
    const tmp = new Uint8Array(W * H);
    const edges = new Uint8Array(W * H);
    const outImage = bctx.createImageData(W, H);

    const reduced = prefersReducedMotion();

    let raf = 0;
    let visible = true;
    let startedAt = 0;
    let lastDraw = 0;
    let renderedElapsed = 0;

    const observer =
      typeof IntersectionObserver === 'function'
        ? new IntersectionObserver(
            (entries) => {
              visible = entries.some((e) => e.isIntersecting);
            },
            {threshold: 0.05},
          )
        : null;
    observer?.observe(wrap);

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

    /** Blits the offscreen buffer to the visible canvas, cropped to a horizontal band. */
    const blitBand = (y0: number, y1: number) => {
      const h = y1 - y0;
      if (h <= 0) return;
      const scale = canvas.height / H;
      ctx.drawImage(buf, 0, y0, W, h, 0, y0 * scale, canvas.width, h * scale);
    };

    /**
     * The one frame drawn when motion is suppressed: the final stage, with every target acquired.
     * It is the most informative single picture of the sequence.
     *
     * `renderStage` is a pure function of its arguments, so a fixed `t` gives a fixed composition —
     * which is what makes it safe to call this again after a resize.
     */
    const drawStatic = () => {
      renderStage('detect', 6.2, 1);
      blitBand(0, H);
    };

    const resize = () => {
      const rect = wrap.getBoundingClientRect();
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const cssW = Math.max(1, rect.width);
      const cssH = cssW * (H / W);
      canvas.style.height = `${cssH}px`;
      // Assigning width/height RESETS the canvas — it clears the bitmap and the context state.
      // Paused and reduced-motion views have no next painted frame, so restore their frozen
      // composition here instead of waiting for the animation clock to advance.
      canvas.width = Math.round(cssW * dpr);
      canvas.height = Math.round(cssH * dpr);
      ctx.imageSmoothingEnabled = true;
      if (reduced) {
        drawStatic();
      } else if (pausedRef.current) {
        drawFrame(renderedElapsed);
      }
    };

    /** Draws a single composed frame for the given elapsed time. */
    const drawFrame = (elapsed: number) => {
      renderedElapsed = elapsed;
      const t = elapsed / 1000;
      const cycle = Math.floor(elapsed / CYCLE_MS);
      const withinCycle = elapsed % CYCLE_MS;
      const current = cycle % STAGES.length;
      const next = (current + 1) % STAGES.length;
      const morphing = withinCycle > HOLD_MS;

      if (!morphing) {
        renderStage(STAGES[current].id, t, withinCycle / HOLD_MS);
        blitBand(0, H);
      } else {
        // Scan wipe: the incoming stage occupies the band above the scan line, the outgoing stage
        // the band below it. Drawing the outgoing stage first means one blit each, no compositing.
        const p = (withinCycle - HOLD_MS) / MORPH_MS;
        const eased = p * p * (3 - 2 * p); // smoothstep
        const scanY = Math.round(eased * H);

        renderStage(STAGES[current].id, t, 1);
        blitBand(scanY, H);
        // The incoming stage is only just starting, so its reveal progress is still near zero.
        renderStage(STAGES[next].id, t, p * (MORPH_MS / HOLD_MS));
        blitBand(0, scanY);

        // The scan line itself: a bright cyan rule with a soft leading glow.
        const scale = canvas.height / H;
        const y = scanY * scale;
        const g = ctx.createLinearGradient(0, y - 26, 0, y + 3);
        g.addColorStop(0, 'rgba(126,233,245,0)');
        g.addColorStop(1, 'rgba(126,233,245,0.30)');
        ctx.fillStyle = g;
        ctx.fillRect(0, y - 26, canvas.width, 26);
        ctx.fillStyle = 'rgba(180,245,255,0.95)';
        ctx.fillRect(0, y - 1, canvas.width, 2);
      }

      const shown = morphing && withinCycle - HOLD_MS > MORPH_MS / 2 ? next : current;
      setStageIndex((prev) => (prev === shown ? prev : shown));
    };

    resize();
    const ro = typeof ResizeObserver === 'function' ? new ResizeObserver(resize) : null;
    ro?.observe(wrap);

    if (reduced) {
      // One frame, held. The draw itself already happened inside `resize()` above, and will happen
      // again on any later resize; all that is left is to point the caption at the stage on screen.
      setStageIndex(STAGES.length - 1);
      return () => {
        observer?.disconnect();
        ro?.disconnect();
      };
    }

    const loop = (now: number) => {
      raf = requestAnimationFrame(loop);
      if (!visible || document.hidden || pausedRef.current) {
        // Keep the clock from advancing while parked, so returning to the tab resumes the sequence
        // where it left off instead of jumping several stages.
        startedAt += now - lastDraw;
        lastDraw = now;
        return;
      }
      // Cap at ~30fps. The pipeline is recomputed from scratch every frame; 60 would double the
      // cost for a difference nobody can see on a 2.1-second dwell.
      if (now - lastDraw < 33) return;
      if (startedAt === 0) startedAt = now;
      lastDraw = now;
      drawFrame(now - startedAt);
    };

    raf = requestAnimationFrame((now) => {
      startedAt = now;
      lastDraw = now;
      loop(now);
    });

    return () => {
      cancelAnimationFrame(raf);
      observer?.disconnect();
      ro?.disconnect();
    };
  }, []);

  const stage = STAGES[stageIndex];

  return (
    <figure className={styles.frame}>
      <div className={styles.viewport} ref={wrapRef}>
        <canvas
          ref={canvasRef}
          className={styles.canvas}
          role="img"
          aria-label={
            'An animated demonstration of a computer-vision pipeline: a synthetic robot workbench ' +
            'is converted to greyscale, blurred, edge-detected, traced into contours, and finally ' +
            'annotated with labelled detection boxes.'
          }
        />

        {/* HUD chrome. Purely decorative, hidden from assistive technology — the canvas above
            already carries the description. */}
        <div className={styles.hud} aria-hidden="true">
          <span className={`${styles.bracket} ${styles.tl}`} />
          <span className={`${styles.bracket} ${styles.tr}`} />
          <span className={`${styles.bracket} ${styles.bl}`} />
          <span className={`${styles.bracket} ${styles.br}`} />
          <div className={styles.hudTop}>
            <span className={styles.rec}>
              <i className={styles.recDot} />
              live
            </span>
            <span>
              {W}×{H}
            </span>
            <span>{stage.format}</span>
          </div>
          <div className={styles.hudBottom}>
            <span>
              stage {String(stageIndex + 1).padStart(2, '0')}/{String(STAGES.length).padStart(2, '0')}
            </span>
            <span className={styles.hudStage}>{stage.label}</span>
          </div>
        </div>

        {live && (
          <button
            type="button"
            className={styles.pause}
            onClick={togglePaused}
            aria-pressed={paused}
            title={paused ? 'Resume the animation' : 'Pause the animation'}>
            {paused ? '▶ resume' : '❚❚ pause'}
          </button>
        )}
      </div>

      <figcaption className={styles.caption}>
        <ol className={styles.track} aria-label="Pipeline stages">
          {STAGES.map((s, i) => (
            <li
              key={s.id}
              className={i === stageIndex ? `${styles.tick} ${styles.tickOn}` : styles.tick}>
              <span className={styles.srOnly}>{s.label}</span>
            </li>
          ))}
        </ol>
        <code className={styles.code}>{stage.code}</code>
        <p className={styles.hint}>{stage.hint}</p>
      </figcaption>
    </figure>
  );
}
