import { useMemo } from 'react';
import { Link } from 'react-router';
import { formatPercent, parseScaled, PRICE_SCALE } from '../format/decimal';
import { formatPrice } from '../format/money';
import { useQuotes } from '../realtime/RealtimeProvider';
import type { Quote } from '../realtime/StreamClient';
import { useWatchlists } from './useWatchlists';
import styles from './Watchlists.module.css';

/** Change since the previous close, as a display percentage. */
function change(quote: Quote | undefined): { text: string; negative: boolean } | null {
  if (!quote?.prevClose) {
    return null;
  }
  const last = parseScaled(quote.last, PRICE_SCALE);
  const prev = parseScaled(quote.prevClose, PRICE_SCALE);
  return { text: formatPercent(Number(last - prev), Number(prev)), negative: last < prev };
}

export function Watchlists() {
  const lists = useWatchlists();
  const symbols = useMemo(() => [...new Set(lists.data?.flatMap((l) => l.symbols) ?? [])], [lists.data]);
  const quotes = useQuotes(symbols);
  const bySymbol = new Map(symbols.map((s, i) => [s, quotes[i]]));

  if (!lists.data || lists.data.length === 0) {
    return <p className={styles.muted}>Add stocks to a list with the ☆ on any stock page.</p>;
  }
  return (
    <>
      {lists.data.map((list) => (
        <section key={list.id} aria-label={list.name}>
          <h3 className={styles.name}>{list.name}</h3>
          {list.symbols.length === 0 ? (
            <p className={styles.muted}>Empty</p>
          ) : (
            <ul className={styles.list}>
              {list.symbols.map((symbol) => {
                const quote = bySymbol.get(symbol);
                const delta = change(quote);
                return (
                  <li key={symbol}>
                    <Link to={`/stocks/${symbol}`} className={styles.row} data-testid={`watch-${symbol}`}>
                      <span className={styles.symbol}>{symbol}</span>
                      <span className={styles.numbers}>
                        <span>{quote ? formatPrice(quote.last) : '—'}</span>
                        <span className={delta?.negative ? styles.negative : styles.positive}>{delta?.text ?? ''}</span>
                      </span>
                    </Link>
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      ))}
    </>
  );
}
