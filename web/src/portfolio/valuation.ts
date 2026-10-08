import { changeCents, notionalCents } from '../format/decimal';
import type { Quote } from '../realtime/StreamClient';
import type { PortfolioView, PositionView } from './types';

export interface LivePosition extends PositionView {
  /** Value at the live price (or the server's value if no live quote has arrived yet). */
  valueCents: number | null;
  dayChangeCents: number | null;
  /** The stream-service quote the values were computed from, if any. */
  quote?: Quote;
}

export interface Valuation {
  totalCents: number | null;
  dayChangeCents: number | null;
  positions: LivePosition[];
}

/**
 * Portfolio value at live prices: cash + reserved cash + sum of qty x last, each position rounded half-even to the
 * cent like portfolio-service does. Positions without a live quote fall back to the REST values.
 */
export function valuate(portfolio: PortfolioView, quotes: (Quote | undefined)[]): Valuation {
  let total: number | null = portfolio.cashCents + portfolio.cashReservedCents;
  let day: number | null = 0;
  const positions = portfolio.positions.map((p, i): LivePosition => {
    const quote = quotes[i];
    const valueCents = quote ? notionalCents(p.qty, quote.last) : p.marketValueCents;
    const dayChange = quote?.prevClose !== undefined ? changeCents(p.qty, quote.last, quote.prevClose) : p.dayChangeCents;
    total = total === null || valueCents === null ? null : total + valueCents;
    day = day === null || dayChange === null ? null : day + dayChange;
    return { ...p, last: quote?.last ?? p.last, valueCents, dayChangeCents: dayChange, quote };
  });
  return { totalCents: total, dayChangeCents: day, positions };
}
