import { useMemo, useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { isValidLimitPrice, isValidQty, formatQty, notionalCents, parseDollarsToCents, sharesForDollars }
  from '../format/decimal';
import { formatCents, formatPrice } from '../format/money';
import { placeOrder, type PlaceOrder } from '../orders/api';
import { orderErrorText } from '../orders/messages';
import type { Quote } from '../realtime/StreamClient';
import { useToast } from '../toast/ToastProvider';
import styles from './TradePanel.module.css';

type Side = 'BUY' | 'SELL';
type OrderType = 'MARKET' | 'LIMIT';
type AmountMode = 'SHARES' | 'DOLLARS';

interface Props {
  symbol: string;
  quote: Quote | undefined;
  /** Shares held, as a decimal string; null if none. */
  heldQty: string | null;
  buyingPowerCents: number | null;
}

export interface Draft {
  order: PlaceOrder;
  /** True when the quantity was derived from a dollar amount (an estimate). */
  estimatedQty: boolean;
  referencePrice: string;
  estimatedCents: number;
}

/**
 * Turns the form into an order, or an error message. Dollar amounts become shares at the reference price (ask
 * to buy, bid to sell, or the limit price), rounded down to 6 decimals: an estimate, since the fill decides.
 */
export function buildDraft(input: { symbol: string; side: Side; type: OrderType; mode: AmountMode; amount: string;
  limitPrice: string; timeInForce: 'DAY' | 'GTC'; quote: Quote | undefined }): Draft | string {
  const { symbol, side, type, mode, amount, limitPrice, timeInForce, quote } = input;
  if (type === 'LIMIT' && !isValidLimitPrice(limitPrice.trim())) {
    return 'Enter a limit price in dollars and cents.';
  }
  const referencePrice = type === 'LIMIT' ? limitPrice.trim() : side === 'BUY' ? quote?.ask : quote?.bid;
  if (!referencePrice) {
    return 'Waiting for a price…';
  }
  let qty: string;
  if (mode === 'DOLLARS') {
    const cents = parseDollarsToCents(amount);
    if (cents === null || cents <= 0) {
      return 'Enter a dollar amount.';
    }
    qty = sharesForDollars(cents, referencePrice);
    if (!isValidQty(qty)) {
      return 'That amount is less than 0.000001 shares.';
    }
  } else {
    qty = amount.trim();
    if (!isValidQty(qty)) {
      return 'Enter a number of shares (up to 6 decimals).';
    }
  }
  const order: PlaceOrder = type === 'LIMIT'
    ? { symbol, side, type, qty, limitPrice: limitPrice.trim(), timeInForce }
    : { symbol, side, type, qty, timeInForce: 'DAY' };
  return { order, estimatedQty: mode === 'DOLLARS', referencePrice, estimatedCents: notionalCents(qty, referencePrice) };
}

export function TradePanel({ symbol, quote, heldQty, buyingPowerCents }: Props) {
  const [side, setSide] = useState<Side>('BUY');
  const [type, setType] = useState<OrderType>('MARKET');
  const [mode, setMode] = useState<AmountMode>('SHARES');
  const [amount, setAmount] = useState('');
  const [limitPrice, setLimitPrice] = useState('');
  const [timeInForce, setTimeInForce] = useState<'DAY' | 'GTC'>('GTC');
  // While reviewing, the order and its idempotency key are frozen: resubmitting retries the same order.
  const [review, setReview] = useState<{ draft: Draft; key: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const toast = useToast();

  const draft = useMemo(() => buildDraft({ symbol, side, type, mode, amount, limitPrice, timeInForce, quote }),
    [symbol, side, type, mode, amount, limitPrice, timeInForce, quote]);

  const submit = useMutation({
    mutationFn: (r: { draft: Draft; key: string }) => placeOrder(r.draft.order, r.key),
    onSuccess: (order) => {
      toast.show(`${order.side === 'BUY' ? 'Buy' : 'Sell'} order for ${formatQty(order.qty)} ${order.symbol} submitted`,
        { tone: 'info', tag: `order-submitted-${order.id}` });
      setReview(null);
      setAmount('');
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
      void queryClient.invalidateQueries({ queryKey: ['portfolio'] });
    },
    onError: (e) => setError(orderErrorText(e)),
  });

  function change<T>(setter: (v: T) => void) {
    return (value: T) => {
      setter(value);
      setReview(null);
      setError(null);
    };
  }

  function openReview(event: FormEvent) {
    event.preventDefault();
    if (typeof draft === 'string') {
      setError(draft);
      return;
    }
    setError(null);
    setReview({ draft, key: crypto.randomUUID() });
  }

  const verb = side === 'BUY' ? 'Buy' : 'Sell';
  const ready = typeof draft !== 'string';
  const overBudget = ready && side === 'BUY' && buyingPowerCents !== null && draft.estimatedCents > buyingPowerCents;

  return (
    <form className={styles.panel} onSubmit={openReview} aria-label={`Trade ${symbol}`}>
      <div className={styles.sides} role="tablist" aria-label="Side">
        {(['BUY', 'SELL'] as Side[]).map((s) => (
          <button key={s} type="button" role="tab" aria-selected={side === s}
                  className={`${styles.side} ${side === s ? styles.sideActive : ''}`} onClick={() => change(setSide)(s)}>
            {s === 'BUY' ? `Buy ${symbol}` : `Sell ${symbol}`}
          </button>
        ))}
      </div>

      <label className={styles.row}>
        <span>Order type</span>
        <select value={type} onChange={(e) => change(setType)(e.target.value as OrderType)} className={styles.select}>
          <option value="MARKET">Market</option>
          <option value="LIMIT">Limit</option>
        </select>
      </label>

      {type === 'LIMIT' && (
        <>
          <label className={styles.row}>
            <span>Limit price</span>
            <input className={styles.input} inputMode="decimal" placeholder="$0.00" value={limitPrice}
                   onChange={(e) => change(setLimitPrice)(e.target.value)} aria-label="Limit price" />
          </label>
          <label className={styles.row}>
            <span>Expires</span>
            <select value={timeInForce} className={styles.select}
                    onChange={(e) => change(setTimeInForce)(e.target.value as 'DAY' | 'GTC')}>
              <option value="GTC">Good till canceled</option>
              <option value="DAY">Good for day</option>
            </select>
          </label>
        </>
      )}

      <label className={styles.row}>
        <span>{verb} in</span>
        <select value={mode} onChange={(e) => change(setMode)(e.target.value as AmountMode)} className={styles.select}
                aria-label="Amount in">
          <option value="SHARES">Shares</option>
          <option value="DOLLARS">Dollars</option>
        </select>
      </label>

      <label className={styles.row}>
        <span>{mode === 'SHARES' ? 'Shares' : 'Amount'}</span>
        <input className={styles.input} inputMode="decimal" placeholder={mode === 'SHARES' ? '0' : '$0.00'} value={amount}
               onChange={(e) => change(setAmount)(e.target.value)} aria-label={mode === 'SHARES' ? 'Shares' : 'Amount'} />
      </label>

      <div className={styles.row}>
        <span className={styles.muted}>{type === 'MARKET' ? `Market price (${side === 'BUY' ? 'ask' : 'bid'})` : 'Limit price'}</span>
        <span>{ready ? formatPrice(draft.referencePrice) : quote ? formatPrice(side === 'BUY' ? quote.ask : quote.bid) : '—'}</span>
      </div>
      {ready && draft.estimatedQty && (
        <div className={styles.row} data-testid="estimated-shares">
          <span className={styles.muted}>Estimated shares</span>
          <span>≈ {formatQty(draft.order.qty)}</span>
        </div>
      )}
      <div className={`${styles.row} ${styles.total}`}>
        <span>{side === 'BUY' ? 'Estimated cost' : 'Estimated credit'}</span>
        <span data-testid="estimated-total">{ready ? formatCents(draft.estimatedCents) : '—'}</span>
      </div>

      {error && <p className={styles.error} role="alert">{error}</p>}
      {overBudget && !error && <p className={styles.warning}>This is more than your buying power.</p>}

      {review ? (
        <div className={styles.review} aria-label="Review order">
          <p>
            {verb} {review.draft.estimatedQty ? '≈ ' : ''}{formatQty(review.draft.order.qty)} {symbol}{' '}
            {review.draft.order.type === 'LIMIT'
              ? `at ${formatPrice(review.draft.order.limitPrice!)} or better (${review.draft.order.timeInForce === 'GTC'
                ? 'good till canceled' : 'good for day'})`
              : 'at market price'}
            {review.draft.estimatedQty ? '. The share amount is an estimate from the current price.' : '.'}
          </p>
          <div className={styles.actions}>
            <button type="button" className={styles.secondary} onClick={() => setReview(null)} disabled={submit.isPending}>
              Edit
            </button>
            <button type="button" className={styles.primary} disabled={submit.isPending}
                    onClick={() => {
                      setError(null);
                      submit.mutate(review);
                    }}>
              {submit.isPending ? 'Submitting…' : `Submit ${verb.toLowerCase()} order`}
            </button>
          </div>
        </div>
      ) : (
        <button type="submit" className={styles.primary} disabled={!ready}>
          Review order
        </button>
      )}

      <p className={styles.footer}>
        {side === 'BUY'
          ? `${buyingPowerCents === null ? '—' : formatCents(buyingPowerCents)} buying power available`
          : `${heldQty ? formatQty(heldQty) : '0'} shares available`}
      </p>
    </form>
  );
}
