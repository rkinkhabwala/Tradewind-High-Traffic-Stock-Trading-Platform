import type { ChartPoint } from './ValueChart';
import type { HistoryView } from './types';

/**
 * Snapshot series plus the live value as the final point (snapshots are every 5 simulated minutes; the live point
 * keeps the chart's end current). Times are market time, as the snapshots are.
 */
export function chartPoints(history: HistoryView | undefined, liveCents: number | null, liveTs: string | null): ChartPoint[] {
  const points: ChartPoint[] = (history?.points ?? []).map((p) => ({
    time: Math.floor(Date.parse(p.ts) / 1000),
    value: p.valueCents / 100,
  }));
  if (liveCents === null) {
    return points;
  }
  const lastTime = points.length > 0 ? points[points.length - 1].time : 0;
  const liveTime = liveTs ? Math.floor(Date.parse(liveTs) / 1000) : lastTime;
  if (points.length > 0 && liveTime <= lastTime) {
    points[points.length - 1] = { time: lastTime, value: liveCents / 100 };
  } else {
    points.push({ time: liveTime, value: liveCents / 100 });
  }
  return points;
}
