import {W, H} from './scene';

/** Rec. 601 luma, the same weighting OpenCV's `COLOR_BGR2GRAY` uses. */
export function toGray(src: Uint8ClampedArray, out: Uint8Array): void {
  for (let i = 0, p = 0; i < out.length; i++, p += 4) {
    out[i] = (src[p] * 77 + src[p + 1] * 150 + src[p + 2] * 29) >> 8;
  }
}

/**
 * Separable box blur. Two 1-D passes cost O(radius) per pixel instead of the O(radius²) a naive 2-D
 * kernel would, which is exactly why OpenCV's own `blur` is separable too.
 */
export function boxBlur(src: Uint8Array, out: Uint8Array, tmp: Uint8Array, radius: number): void {
  const norm = 1 / (radius * 2 + 1);
  // Horizontal.
  for (let y = 0; y < H; y++) {
    const row = y * W;
    let sum = 0;
    for (let x = -radius; x <= radius; x++) sum += src[row + Math.min(W - 1, Math.max(0, x))];
    for (let x = 0; x < W; x++) {
      tmp[row + x] = sum * norm;
      sum -= src[row + Math.min(W - 1, Math.max(0, x - radius))];
      sum += src[row + Math.min(W - 1, Math.max(0, x + radius + 1))];
    }
  }
  // Vertical.
  for (let x = 0; x < W; x++) {
    let sum = 0;
    for (let y = -radius; y <= radius; y++) sum += tmp[Math.min(H - 1, Math.max(0, y)) * W + x];
    for (let y = 0; y < H; y++) {
      out[y * W + x] = sum * norm;
      sum -= tmp[Math.min(H - 1, Math.max(0, y - radius)) * W + x];
      sum += tmp[Math.min(H - 1, Math.max(0, y + radius + 1)) * W + x];
    }
  }
}

/** Sobel gradient magnitude, thresholded. A stand-in for Canny that costs one pass. */
export function sobel(src: Uint8Array, out: Uint8Array, threshold: number): void {
  out.fill(0);
  for (let y = 1; y < H - 1; y++) {
    for (let x = 1; x < W - 1; x++) {
      const i = y * W + x;
      const tl = src[i - W - 1];
      const tc = src[i - W];
      const tr = src[i - W + 1];
      const ml = src[i - 1];
      const mr = src[i + 1];
      const bl = src[i + W - 1];
      const bc = src[i + W];
      const br = src[i + W + 1];
      const gx = tl + 2 * ml + bl - tr - 2 * mr - br;
      const gy = tl + 2 * tc + tr - bl - 2 * bc - br;
      const mag = Math.abs(gx) + Math.abs(gy); // L1 norm; OpenCV's default for Canny too.
      out[i] = mag > threshold ? 255 : 0;
    }
  }
}
