/** Buffer resolution. Deliberately small: it keeps the per-frame arithmetic cheap and gives the
 *  upscaled result the slightly soft look of an actual machine-vision sensor. */
export const W = 384;
export const H = 240;

export interface SceneObject {
  kind: 'chassis' | 'gear' | 'cube' | 'marker';
  label: string;
  /** Axis-aligned bounding box in buffer pixels, recomputed every frame. */
  x: number;
  y: number;
  w: number;
  h: number;
  confidence: number;
}

/**
 * Draws a small workbench scene: a hexagonal robot chassis tracking left to right, a rotating
 * gear, a tumbling cube and a fiducial marker. The shapes are geometric on purpose — hard edges
 * give the Sobel stage something unambiguous to find, so the edge frame reads as a result rather
 * than as noise.
 *
 * Returns the ground-truth bounding boxes, which the contour and detection stages draw. Using the
 * geometry we already have avoids running a connected-components pass per frame for what is
 * ultimately decoration.
 */
export function drawScene(ctx: CanvasRenderingContext2D, t: number): SceneObject[] {
  const bg = '#0d1524';
  const bgHi = '#16203a';

  // Backdrop: a soft vertical gradient plus a floor line, so the frame has depth to lose when it
  // goes to greyscale.
  const grad = ctx.createLinearGradient(0, 0, 0, H);
  grad.addColorStop(0, bgHi);
  grad.addColorStop(1, bg);
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, W, H);

  ctx.strokeStyle = 'rgba(120,170,220,0.20)';
  ctx.lineWidth = 1;
  for (let gx = 0; gx <= W; gx += 32) {
    ctx.beginPath();
    ctx.moveTo(gx + 0.5, H * 0.62);
    ctx.lineTo(gx + 0.5 + (gx - W / 2) * 0.25, H);
    ctx.stroke();
  }
  ctx.beginPath();
  ctx.moveTo(0, H * 0.62 + 0.5);
  ctx.lineTo(W, H * 0.62 + 0.5);
  ctx.stroke();

  const objects: SceneObject[] = [];

  /* --- Robot chassis: a hexagon on two wheels, driving across the bench. --- */
  const driveT = (t * 0.06) % 1;
  const cx = 60 + driveT * (W - 150);
  const cy = H * 0.62 - 26;
  const r = 30;

  ctx.fillStyle = '#c8442f';
  ctx.beginPath();
  for (let i = 0; i < 6; i++) {
    const a = (Math.PI / 3) * i - Math.PI / 6;
    const px = cx + Math.cos(a) * r;
    const py = cy + Math.sin(a) * r * 0.78;
    if (i === 0) ctx.moveTo(px, py);
    else ctx.lineTo(px, py);
  }
  ctx.closePath();
  ctx.fill();

  // Sensor eye — a bright disc that survives every stage and gives the eye an anchor.
  ctx.fillStyle = '#7ee9f5';
  ctx.beginPath();
  ctx.arc(cx + 10, cy - 4, 6, 0, Math.PI * 2);
  ctx.fill();

  ctx.fillStyle = '#2a3550';
  for (const wx of [cx - 16, cx + 16]) {
    ctx.beginPath();
    ctx.arc(wx, cy + 24, 9, 0, Math.PI * 2);
    ctx.fill();
  }

  objects.push({
    kind: 'chassis',
    label: 'chassis',
    x: cx - r - 2,
    y: cy - r * 0.78 - 2,
    w: r * 2 + 4,
    h: r * 0.78 + 36,
    confidence: 0.97,
  });

  /* --- Gear: a rotating cog, bottom left. --- */
  const gx0 = 74;
  const gy0 = H * 0.62 + 34;
  const gr = 24;
  const teeth = 9;
  ctx.fillStyle = '#ffb454';
  ctx.beginPath();
  for (let i = 0; i < teeth * 2; i++) {
    const a = (Math.PI / teeth) * i + t * 0.5;
    const rad = i % 2 === 0 ? gr : gr * 0.74;
    const px = gx0 + Math.cos(a) * rad;
    const py = gy0 + Math.sin(a) * rad * 0.55;
    if (i === 0) ctx.moveTo(px, py);
    else ctx.lineTo(px, py);
  }
  ctx.closePath();
  ctx.fill();
  ctx.fillStyle = bg;
  ctx.beginPath();
  ctx.ellipse(gx0, gy0, gr * 0.3, gr * 0.3 * 0.55, 0, 0, Math.PI * 2);
  ctx.fill();

  objects.push({
    kind: 'gear',
    label: 'gear',
    x: gx0 - gr - 2,
    y: gy0 - gr * 0.55 - 2,
    w: gr * 2 + 4,
    h: gr * 0.55 * 2 + 4,
    confidence: 0.91,
  });

  /* --- Cube: an isometric box that tumbles, right side. --- */
  const bx = W - 96;
  const by = H * 0.62 + 22 + Math.sin(t * 1.1) * 5;
  const s = 26;
  ctx.fillStyle = '#b39bff';
  ctx.beginPath();
  ctx.moveTo(bx, by - s * 0.55);
  ctx.lineTo(bx + s, by);
  ctx.lineTo(bx, by + s * 0.55);
  ctx.lineTo(bx - s, by);
  ctx.closePath();
  ctx.fill();
  ctx.fillStyle = '#8f74e8';
  ctx.beginPath();
  ctx.moveTo(bx - s, by);
  ctx.lineTo(bx, by + s * 0.55);
  ctx.lineTo(bx, by + s * 1.25);
  ctx.lineTo(bx - s, by + s * 0.7);
  ctx.closePath();
  ctx.fill();
  ctx.fillStyle = '#6f57c0';
  ctx.beginPath();
  ctx.moveTo(bx + s, by);
  ctx.lineTo(bx, by + s * 0.55);
  ctx.lineTo(bx, by + s * 1.25);
  ctx.lineTo(bx + s, by + s * 0.7);
  ctx.closePath();
  ctx.fill();

  objects.push({
    kind: 'cube',
    label: 'crate',
    x: bx - s - 2,
    y: by - s * 0.55 - 2,
    w: s * 2 + 4,
    h: s * 1.8 + 4,
    confidence: 0.88,
  });

  /* --- Fiducial marker: a 4×4 ArUco-style tag pinned to the back wall. --- */
  const mx = W - 74;
  const my = 34;
  const cell = 7;
  ctx.fillStyle = '#e8eefa';
  ctx.fillRect(mx - cell, my - cell, cell * 6, cell * 6);
  ctx.fillStyle = '#12161f';
  // A fixed bit pattern — a real tag, not random noise, so it stays stable frame to frame.
  const bits = [
    [1, 0, 1, 0],
    [0, 1, 1, 0],
    [1, 1, 0, 1],
    [0, 0, 1, 1],
  ];
  for (let r0 = 0; r0 < 4; r0++) {
    for (let c0 = 0; c0 < 4; c0++) {
      if (bits[r0][c0]) ctx.fillRect(mx + c0 * cell, my + r0 * cell, cell, cell);
    }
  }

  objects.push({
    kind: 'marker',
    label: 'aruco:23',
    x: mx - cell - 2,
    y: my - cell - 2,
    w: cell * 6 + 4,
    h: cell * 6 + 4,
    confidence: 1.0,
  });

  return objects;
}
