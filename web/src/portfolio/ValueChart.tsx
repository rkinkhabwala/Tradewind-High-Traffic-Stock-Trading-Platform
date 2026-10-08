import { useEffect, useRef } from 'react';
import { AreaSeries, ColorType, createChart, TickMarkType, type IChartApi, type ISeriesApi, type Time,
  type UTCTimestamp } from 'lightweight-charts';

export interface ChartPoint {
  /** Seconds since the epoch (market time). */
  time: number;
  /** Dollars, for drawing only; amounts are kept in integer cents everywhere else. */
  value: number;
}

/** Times are shown in market time (New York), whatever the viewer's time zone. */
const MARKET_ZONE = 'America/New_York';
const clock = new Intl.DateTimeFormat('en-US', { timeZone: MARKET_ZONE, hour: 'numeric', minute: '2-digit' });
const day = new Intl.DateTimeFormat('en-US', { timeZone: MARKET_ZONE, month: 'short', day: 'numeric' });
const month = new Intl.DateTimeFormat('en-US', { timeZone: MARKET_ZONE, month: 'short' });
const year = new Intl.DateTimeFormat('en-US', { timeZone: MARKET_ZONE, year: 'numeric' });
const full = new Intl.DateTimeFormat('en-US', { timeZone: MARKET_ZONE, month: 'short', day: 'numeric',
  hour: 'numeric', minute: '2-digit', timeZoneName: 'short' });
const dollars = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

const asDate = (time: Time) => new Date((time as number) * 1000);

export function tickLabel(time: Time, type: TickMarkType): string {
  const date = asDate(time);
  switch (type) {
    case TickMarkType.Year:
      return year.format(date);
    case TickMarkType.Month:
      return month.format(date);
    case TickMarkType.DayOfMonth:
      return day.format(date);
    default:
      return clock.format(date);
  }
}

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
}

/** Area chart of a value series; the last point moves with the live value without redrawing everything. */
export function ValueChart({ points, positive, height = 260 }: { points: ChartPoint[]; positive: boolean; height?: number }) {
  const container = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const series = useRef<ISeriesApi<'Area'> | null>(null);
  const drawn = useRef<ChartPoint[]>([]);

  useEffect(() => {
    if (!container.current) {
      return undefined;
    }
    const c = createChart(container.current, {
      height,
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: 'transparent' }, textColor: cssVar('--color-text-muted'),
        attributionLogo: false },
      localization: { timeFormatter: (time: Time) => full.format(asDate(time)), priceFormatter: (v: number) => dollars.format(v) },
      grid: { vertLines: { visible: false }, horzLines: { visible: false } },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false, timeVisible: true, secondsVisible: false, tickMarkFormatter: tickLabel },
      crosshair: { horzLine: { visible: false } },
      handleScroll: false,
      handleScale: false,
    });
    chart.current = c;
    series.current = c.addSeries(AreaSeries, { lineWidth: 2, priceLineVisible: false, lastValueVisible: false });
    return () => {
      c.remove();
      chart.current = null;
      series.current = null;
      drawn.current = [];
    };
  }, [height]);

  useEffect(() => {
    const color = cssVar(positive ? '--color-positive' : '--color-negative');
    series.current?.applyOptions({ lineColor: color, topColor: `${color}55`, bottomColor: `${color}00` });
  }, [positive]);

  useEffect(() => {
    const s = series.current;
    if (!s) {
      return;
    }
    const before = drawn.current;
    const onlyLastMoved = before.length === points.length && points.length > 0
      && before.slice(0, -1).every((p, i) => p.time === points[i].time && p.value === points[i].value)
      && before[before.length - 1].time <= points[points.length - 1].time;
    if (onlyLastMoved) {
      const last = points[points.length - 1];
      s.update({ time: last.time as UTCTimestamp, value: last.value });
    } else {
      s.setData(points.map((p) => ({ time: p.time as UTCTimestamp, value: p.value })));
      chart.current?.timeScale().fitContent();
    }
    drawn.current = points;
  }, [points]);

  return <div ref={container} style={{ height }} data-testid="value-chart" />;
}
