import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { formatPercent, formatQty } from '../format/decimal';
import { formatCents, formatPrice } from '../format/money';
import { chartPoints } from '../portfolio/history';
import type { HistoryRange, HistoryView, PortfolioView } from '../portfolio/types';
import { valuate } from '../portfolio/valuation';
import { ValueChart } from '../portfolio/ValueChart';
import { useQuotes } from '../realtime/RealtimeProvider';
import styles from './HomePage.module.css';

const RANGES: HistoryRange[] = ['1D', '1W', '1M', '1Y'];

function signClass(cents: number | null): string {
  return cents !== null && cents < 0 ? styles.negative : styles.positive;
}

export function HomePage() {
  const [range, setRange] = useState<HistoryRange>('1D');
  const portfolio = useQuery({
    queryKey: ['portfolio'],
    queryFn: ({ signal }) => api<PortfolioView>('/api/portfolio', { signal }),
  });
  const history = useQuery({
    queryKey: ['portfolio-history', range],
    queryFn: ({ signal }) => api<HistoryView>(`/api/portfolio/history?range=${range}`, { signal }),
    refetchInterval: 60_000,
  });

  const symbols = useMemo(() => portfolio.data?.positions.map((p) => p.symbol) ?? [], [portfolio.data]);
  const quotes = useQuotes(symbols);
  const valuation = useMemo(() => (portfolio.data ? valuate(portfolio.data, quotes) : null), [portfolio.data, quotes]);
  const liveTs = useMemo(() => quotes.reduce<string | null>((max, q) => (q && (!max || q.ts > max) ? q.ts : max), null),
    [quotes]);
  const points = useMemo(() => chartPoints(history.data, valuation?.totalCents ?? null, liveTs),
    [history.data, valuation?.totalCents, liveTs]);

  if (portfolio.isPending) {
    return <p className={styles.muted}>Loading portfolio…</p>;
  }
  if (portfolio.isError || !valuation) {
    return <p role="alert">Could not load your portfolio. It will retry automatically.</p>;
  }
  const p = portfolio.data;
  const total = valuation.totalCents;
  const day = valuation.dayChangeCents;
  const rangeChange = total !== null && points.length > 1 ? total - Math.round(points[0].value * 100) : null;
  const shownChange = range === '1D' ? day : rangeChange;

  return (
    <div className={styles.layout}>
      <section aria-label="Portfolio">
        <p className={styles.label}>Portfolio value</p>
        <p className={styles.value} data-testid="portfolio-value">{total === null ? '—' : formatCents(total)}</p>
        <p className={`${styles.change} ${signClass(shownChange)}`} data-testid="portfolio-change">
          {shownChange === null || total === null
            ? ' '
            : `${formatCents(shownChange, { signed: true })} (${formatPercent(shownChange, total - shownChange)}) ${
              range === '1D' ? 'Today' : `Past ${range}`}`}
        </p>
        <ValueChart points={points} positive={(shownChange ?? 0) >= 0} />
        <div className={styles.ranges} role="tablist" aria-label="Chart range">
          {RANGES.map((r) => (
            <button key={r} type="button" role="tab" aria-selected={r === range}
                    className={`${styles.range} ${r === range ? styles.rangeActive : ''}`} onClick={() => setRange(r)}>
              {r}
            </button>
          ))}
        </div>
        <div className={styles.buyingPower}>
          <span>Buying power</span>
          <span data-testid="buying-power">{formatCents(p.buyingPowerCents)}</span>
        </div>
        {p.cashReservedCents > 0 && (
          <div className={styles.buyingPower}>
            <span className={styles.muted}>Reserved for open orders</span>
            <span className={styles.muted}>{formatCents(p.cashReservedCents)}</span>
          </div>
        )}
      </section>

      <aside className={styles.side} aria-label="Positions">
        <h2 className={styles.sideTitle}>Stocks</h2>
        {valuation.positions.length === 0 ? (
          <p className={styles.muted}>No positions yet.</p>
        ) : (
          <ul className={styles.positions}>
            {valuation.positions.map((position) => (
              <li key={position.symbol} className={styles.position} data-testid={`position-${position.symbol}`}>
                <div>
                  <div className={styles.symbol}>{position.symbol}</div>
                  <div className={styles.qty}>{formatQty(position.qty)} shares</div>
                </div>
                <div className={styles.numbers}>
                  <div>{position.last ? formatPrice(position.last) : '—'}</div>
                  <div className={`${styles.qty} ${signClass(position.dayChangeCents)}`}>
                    {position.dayChangeCents === null || position.valueCents === null
                      ? '—'
                      : formatPercent(position.dayChangeCents, position.valueCents - position.dayChangeCents)}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </aside>
    </div>
  );
}
