import { useEffect, useRef } from 'react';
import { CandlestickSeries, ColorType, createChart, type IChartApi, type ISeriesApi, type Time, type UTCTimestamp }
  from 'lightweight-charts';
import { asDate, cssVar, dollars, full, tickLabel } from '../portfolio/ValueChart';
import type { DrawnCandle } from './liveCandles';

/** Candlesticks; when only the last candle changed (a live tick) it is updated in place. */
export function CandleChart({ candles, height = 320 }: { candles: DrawnCandle[]; height?: number }) {
  const container = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const series = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const drawn = useRef<DrawnCandle[]>([]);

  useEffect(() => {
    if (!container.current) {
      return undefined;
    }
    const up = cssVar('--color-positive');
    const down = cssVar('--color-negative');
    const c = createChart(container.current, {
      height,
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: 'transparent' }, textColor: cssVar('--color-text-muted'),
        attributionLogo: false },
      localization: { timeFormatter: (time: Time) => full.format(asDate(time)), priceFormatter: (v: number) => dollars.format(v) },
      grid: { vertLines: { visible: false }, horzLines: { color: cssVar('--color-border') } },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false, timeVisible: true, secondsVisible: false, tickMarkFormatter: tickLabel },
    });
    chart.current = c;
    series.current = c.addSeries(CandlestickSeries, { upColor: up, downColor: down, borderVisible: false,
      wickUpColor: up, wickDownColor: down });
    return () => {
      c.remove();
      chart.current = null;
      series.current = null;
      drawn.current = [];
    };
  }, [height]);

  useEffect(() => {
    const s = series.current;
    if (!s) {
      return;
    }
    const before = drawn.current;
    const sameHistory = before.length > 0 && candles.length >= before.length && candles.length <= before.length + 1
      && before.slice(0, -1).every((c, i) => c === candles[i]);
    if (sameHistory) {
      for (const c of candles.slice(before.length - 1)) {
        s.update({ ...c, time: c.time as UTCTimestamp });
      }
    } else {
      s.setData(candles.map((c) => ({ ...c, time: c.time as UTCTimestamp })));
      chart.current?.timeScale().fitContent();
    }
    drawn.current = candles;
  }, [candles]);

  return <div ref={container} style={{ height }} data-testid="candle-chart" />;
}
