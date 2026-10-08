import { describe, expect, it } from 'vitest';
import { applyTick, type DrawnCandle } from './liveCandles';

const t0 = Date.parse('2026-01-05T15:00:00Z') / 1000;
const base: DrawnCandle[] = [{ time: t0, open: 10, high: 11, low: 9, close: 10.5 }];

describe('applyTick', () => {
  it('extends the current bin', () => {
    const next = applyTick(base, 300, '2026-01-05T15:04:59Z', 12);
    expect(next).toEqual([{ time: t0, open: 10, high: 12, low: 9, close: 12 }]);
  });

  it('opens the next bin aligned to the interval', () => {
    const next = applyTick(base, 300, '2026-01-05T15:07:30Z', 8);
    expect(next).toHaveLength(2);
    expect(next[1]).toEqual({ time: t0 + 300, open: 8, high: 8, low: 8, close: 8 });
  });

  it('ignores ticks older than the series and only extends daily candles', () => {
    expect(applyTick(base, 300, '2026-01-05T14:00:00Z', 1)).toBe(base);
    expect(applyTick(base, 86_400, '2026-01-07T15:00:00Z', 7, true)[0].close).toBe(7);
    expect(applyTick([], 86_400, '2026-01-07T15:00:00Z', 7, true)).toEqual([]);
  });
});
