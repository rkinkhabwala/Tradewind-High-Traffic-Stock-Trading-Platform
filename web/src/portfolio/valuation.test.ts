import { describe, expect, it } from 'vitest';
import type { Quote } from '../realtime/StreamClient';
import { chartPoints } from './history';
import type { PortfolioView } from './types';
import { valuate } from './valuation';

const portfolio: PortfolioView = {
  accountId: 'a', cashCents: 9_000_000, cashReservedCents: 50_000, buyingPowerCents: 9_000_000,
  positions: [
    { symbol: 'AAPL', qty: '10.000000', avgCost: '190.0000', last: '200.0000', marketValueCents: 200_000,
      unrealizedPnlCents: 10_000, dayChangeCents: 5_000 },
    { symbol: 'TSLA', qty: '2.500000', avgCost: '250.0000', last: '250.0000', marketValueCents: 62_500,
      unrealizedPnlCents: 0, dayChangeCents: -1_000 },
  ],
  positionsValueCents: 262_500, totalValueCents: 9_312_500, dayChangeCents: 4_000, livePrices: true,
};

const quote = (symbol: string, last: string, prevClose?: string): Quote =>
  ({ symbol, bid: last, ask: last, last, bidSize: 1, askSize: 1, prevClose, ts: '2026-01-05T15:30:00Z' });

describe('valuate', () => {
  it('uses the REST values until live quotes arrive', () => {
    const v = valuate(portfolio, [undefined, undefined]);
    expect(v.totalCents).toBe(9_312_500);
    expect(v.dayChangeCents).toBe(4_000);
  });

  it('revalues each position at the live price, cash and reserved cash included', () => {
    const v = valuate(portfolio, [quote('AAPL', '210.0000', '195.0000'), quote('TSLA', '240.1234')]);
    // 10 x 210 = 2100.00; 2.5 x 240.1234 = 600.3085 -> 600.31
    expect(v.positions.map((p) => p.valueCents)).toEqual([210_000, 60_031]);
    expect(v.totalCents).toBe(9_000_000 + 50_000 + 210_000 + 60_031);
    // AAPL: 10 x (210 - 195) = 150.00 live; TSLA has no prevClose yet: server's -10.00
    expect(v.dayChangeCents).toBe(15_000 - 1_000);
    expect(v.positions[1].last).toBe('240.1234');
  });

  it('cannot value an unpriced position', () => {
    const unpriced = { ...portfolio, positions: [{ ...portfolio.positions[0], marketValueCents: null, dayChangeCents: null }] };
    expect(valuate(unpriced, [undefined]).totalCents).toBeNull();
  });
});

describe('chartPoints', () => {
  const history = { range: '1D' as const, points: [
    { ts: '2026-01-05T14:30:00Z', valueCents: 100_000 },
    { ts: '2026-01-05T14:35:00Z', valueCents: 100_500 },
  ] };

  it('appends the live value as the last point', () => {
    const points = chartPoints(history, 101_000, '2026-01-05T14:37:12Z');
    expect(points).toHaveLength(3);
    expect(points[2]).toEqual({ time: Date.parse('2026-01-05T14:37:12Z') / 1000, value: 1010 });
  });

  it('replaces the last point instead of going back in time', () => {
    const points = chartPoints(history, 101_000, '2026-01-05T14:35:00Z');
    expect(points).toHaveLength(2);
    expect(points[1].value).toBe(1010);
  });

  it('shows just the live point before the first snapshot exists', () => {
    expect(chartPoints({ range: '1D', points: [] }, 5_000, '2026-01-05T14:31:00Z')).toHaveLength(1);
    expect(chartPoints(undefined, null, null)).toEqual([]);
  });
});
