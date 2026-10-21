/** How long each stage holds, and how long the morph between two stages takes (milliseconds). */
export const HOLD_MS = 2100;
export const MORPH_MS = 900;
export const CYCLE_MS = HOLD_MS + MORPH_MS;

export function frameAt(elapsed: number, stageCount: number) {
  const t = elapsed / 1000;
  const cycle = Math.floor(elapsed / CYCLE_MS);
  const withinCycle = elapsed % CYCLE_MS;
  const current = cycle % stageCount;
  const next = (current + 1) % stageCount;
  const morphing = withinCycle > HOLD_MS;
  const shown = morphing && withinCycle - HOLD_MS > MORPH_MS / 2 ? next : current;
  return {t, current, next, withinCycle, morphing, shown};
}
