import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { formatCents } from '../format/money';
import styles from './HomePage.module.css';

/** Subset of GET /api/portfolio used here; milestone p adds the chart, positions and live updates. */
export interface PortfolioSummary {
  cashCents: number;
  cashReservedCents: number;
  buyingPowerCents: number;
  totalValueCents: number | null;
  livePrices: boolean;
}

export function HomePage() {
  const portfolio = useQuery({
    queryKey: ['portfolio'],
    queryFn: ({ signal }) => api<PortfolioSummary>('/api/portfolio', { signal }),
  });

  if (portfolio.isPending) {
    return <p className={styles.muted}>Loading portfolio…</p>;
  }
  if (portfolio.isError) {
    return <p role="alert">Could not load your portfolio. It will retry automatically.</p>;
  }
  const p = portfolio.data;
  return (
    <section aria-label="Portfolio">
      <p className={styles.label}>Portfolio value</p>
      <p className={styles.value}>{p.totalValueCents === null ? '—' : formatCents(p.totalValueCents)}</p>
      <dl className={styles.facts}>
        <div>
          <dt>Buying power</dt>
          <dd>{formatCents(p.buyingPowerCents)}</dd>
        </div>
        <div>
          <dt>Reserved for open orders</dt>
          <dd>{formatCents(p.cashReservedCents)}</dd>
        </div>
      </dl>
    </section>
  );
}
