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
import {W, H} from './scene';
import {createStageRenderer} from './renderer';
import type {StageId} from './renderer';
import {HOLD_MS, MORPH_MS, frameAt} from './timing';

/* -------------------------------------------------------------------------- */
/* Stage definitions                                                          */
/* -------------------------------------------------------------------------- */

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

    const renderStage = createStageRenderer(bctx);

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
      const {t, current, next, withinCycle, morphing, shown} = frameAt(elapsed, STAGES.length);

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
