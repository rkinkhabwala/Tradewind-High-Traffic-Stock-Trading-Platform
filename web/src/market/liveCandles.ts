import type { Candle } from './api';

/** A candle as drawn: time in epoch seconds (bin start, market time), prices as numbers for drawing only. */
export interface DrawnCandle {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
}

export function toDrawn(candles: Candle[]): DrawnCandle[] {
  return candles.map((c) => ({
    time: Math.floor(Date.parse(c.start) / 1000),
    open: Number(c.open),
    high: Number(c.high),
    low: Number(c.low),
    close: Number(c.close),
  }));
}

/**
 * Folds one live trade price into the series: extends the current bin, or opens the next one. Daily bins come
 * from the server (session-aligned) and are only extended, never opened, client-side.
 */
export function applyTick(series: DrawnCandle[], intervalSeconds: number, tsIso: string, price: number,
                          daily = false): DrawnCandle[] {
  const t = Math.floor(Date.parse(tsIso) / 1000);
  const last = series[series.length - 1];
  if (last && (daily || t < last.time + intervalSeconds)) {
    if (t < last.time) {
      return series;          // older than what is drawn
    }
    const updated = { ...last, high: Math.max(last.high, price), low: Math.min(last.low, price), close: price };
    return [...series.slice(0, -1), updated];
  }
  if (daily) {
    return series;
  }
  const start = t - (t % intervalSeconds);
  return [...series, { time: start, open: price, high: price, low: price, close: price }];
}
