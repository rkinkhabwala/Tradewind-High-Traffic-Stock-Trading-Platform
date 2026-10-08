import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router';
import { ApiError } from '../api/client';
import { changeCents, formatPercent, formatQty, notionalCents, parseScaled, PRICE_SCALE } from '../format/decimal';
import { formatCents, formatPrice } from '../format/money';
import { getCandles, getSymbol, INTERVAL_SECONDS, type CandleInterval } from '../market/api';
import { CandleChart } from '../market/CandleChart';
import { applyTick, toDrawn, type DrawnCandle } from '../market/liveCandles';
import { usePortfolio } from '../portfolio/queries';
import { useQuote } from '../realtime/RealtimeProvider';
import { TradePanel } from '../trade/TradePanel';
import { useToggleWatch } from '../watchlist/useWatchlists';
import { NotFoundPage } from './NotFoundPage';
import styles from './SymbolPage.module.css';

const INTERVALS: CandleInterval[] = ['1m', '5m', '1h', '1d'];

export function SymbolPage() {
  const symbol = (useParams().symbol ?? '').toUpperCase();
  const [interval, setInterval] = useState<CandleInterval>('5m');
  const info = useQuery({ queryKey: ['symbol', symbol], queryFn: ({ signal }) => getSymbol(symbol, signal) });
  const history = useQuery({
    queryKey: ['candles', symbol, interval],
    queryFn: ({ signal }) => getCandles(symbol, interval, signal),
    enabled: info.isSuccess,
  });
  const quote = useQuote(info.isSuccess ? symbol : undefined);
  const portfolio = usePortfolio();
  const watch = useToggleWatch(symbol);

  // Live candles: start from the server's history, then fold in each tick.
  const [candles, setCandles] = useState<DrawnCandle[]>([]);
  useEffect(() => {
    setCandles(history.data ? toDrawn(history.data) : []);
  }, [history.data]);
  useEffect(() => {
    if (quote) {
      setCandles((series) => applyTick(series, INTERVAL_SECONDS[interval], quote.ts, Number(quote.last), interval === '1d'));
    }
  }, [quote, interval]);

  const position = portfolio.data?.positions.find((p) => p.symbol === symbol);
  const last = quote?.last ?? info.data?.last;
  const prevClose = quote?.prevClose ?? info.data?.prevClose;
  const dayChange = useMemo(() => {
    if (!last || !prevClose) {
      return null;
    }
    const diff = parseScaled(last, PRICE_SCALE) - parseScaled(prevClose, PRICE_SCALE);
    return { cents: changeCents('1', last, prevClose), pct: formatPercent(Number(diff), Number(parseScaled(prevClose, PRICE_SCALE))) };
  }, [last, prevClose]);

  if (info.isError) {
    return info.error instanceof ApiError && info.error.status === 404
      ? <NotFoundPage />
      : <p role="alert">Could not load {symbol}.</p>;
  }
  if (!info.data) {
    return <p className={styles.muted}>Loading {symbol}…</p>;
  }

  const valueCents = position && last ? notionalCents(position.qty, last) : null;
  const costCents = position ? notionalCents(position.qty, position.avgCost) : null;

  return (
    <div className={styles.layout}>
      <section aria-label={symbol}>
        <div className={styles.titleRow}>
          <div>
            <h1 className={styles.name}>{info.data.name}</h1>
            <p className={styles.muted}>{symbol} · {info.data.sector}</p>
          </div>
          <button type="button" className={styles.watch} aria-pressed={watch.watching} disabled={watch.toggle.isPending}
                  onClick={() => watch.toggle.mutate()}>
            {watch.watching ? '★ In list' : '☆ Add to list'}
          </button>
        </div>
        <p className={styles.price} data-testid="symbol-price">{last ? formatPrice(last) : '—'}</p>
        {dayChange && (
          <p className={`${styles.change} ${dayChange.cents < 0 ? styles.negative : styles.positive}`}>
            {formatCents(dayChange.cents, { signed: true })} ({dayChange.pct}) Today
          </p>
        )}
        <CandleChart candles={candles} />
        <div className={styles.ranges} role="tablist" aria-label="Candle interval">
          {INTERVALS.map((i) => (
            <button key={i} type="button" role="tab" aria-selected={i === interval}
                    className={`${styles.range} ${i === interval ? styles.rangeActive : ''}`} onClick={() => setInterval(i)}>
              {i}
            </button>
          ))}
        </div>
        {quote && (
          <dl className={styles.facts}>
            <div><dt>Bid</dt><dd>{formatPrice(quote.bid)} × {quote.bidSize}</dd></div>
            <div><dt>Ask</dt><dd>{formatPrice(quote.ask)} × {quote.askSize}</dd></div>
            <div><dt>Previous close</dt><dd>{prevClose ? formatPrice(prevClose) : '—'}</dd></div>
          </dl>
        )}
        {position && valueCents !== null && costCents !== null && (
          <section className={styles.position} aria-label="Your position">
            <h2 className={styles.sectionTitle}>Your position</h2>
            <dl className={styles.facts}>
              <div><dt>Shares</dt><dd data-testid="position-shares">{formatQty(position.qty)}</dd></div>
              <div><dt>Market value</dt><dd>{formatCents(valueCents)}</dd></div>
              <div><dt>Average cost</dt><dd>{formatPrice(position.avgCost)}</dd></div>
              <div>
                <dt>Total return</dt>
                <dd className={valueCents - costCents < 0 ? styles.negative : styles.positive}>
                  {formatCents(valueCents - costCents, { signed: true })}
                </dd>
              </div>
            </dl>
          </section>
        )}
      </section>
      <aside>
        <TradePanel symbol={symbol} quote={quote} heldQty={position?.qty ?? null}
                    buyingPowerCents={portfolio.data?.buyingPowerCents ?? null} />
      </aside>
    </div>
  );
}
