/** GET /api/portfolio (portfolio-service). Amounts in integer cents; prices and quantities as decimal strings. */
export interface PositionView {
  symbol: string;
  qty: string;
  avgCost: string;
  last: string | null;
  marketValueCents: number | null;
  unrealizedPnlCents: number | null;
  dayChangeCents: number | null;
}

export interface PortfolioView {
  accountId: string;
  cashCents: number;
  cashReservedCents: number;
  buyingPowerCents: number;
  positions: PositionView[];
  positionsValueCents: number | null;
  totalValueCents: number | null;
  dayChangeCents: number | null;
  livePrices: boolean;
}

export type HistoryRange = '1D' | '1W' | '1M' | '1Y';

export interface HistoryView {
  range: HistoryRange;
  points: { ts: string; valueCents: number }[];
}
