import { api } from '../api/client';

export interface SymbolView {
  symbol: string;
  name: string;
  sector: string;
  last: string;
  prevClose: string;
  dayChange: string;
  dayChangePercent: string;
  ts: string | null;
}

export interface Candle {
  start: string;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: number;
}

export interface SearchResult {
  symbol: string;
  name: string;
  sector: string;
}

export type CandleInterval = '1m' | '5m' | '1h' | '1d';

export const INTERVAL_SECONDS: Record<CandleInterval, number> = { '1m': 60, '5m': 300, '1h': 3600, '1d': 86_400 };

export function getSymbol(symbol: string, signal?: AbortSignal): Promise<SymbolView> {
  return api<SymbolView>(`/api/symbols/${encodeURIComponent(symbol)}`, { signal });
}

export function getCandles(symbol: string, interval: CandleInterval, signal?: AbortSignal): Promise<Candle[]> {
  return api<Candle[]>(`/api/symbols/${encodeURIComponent(symbol)}/candles?interval=${interval}`, { signal });
}

export function search(q: string, signal?: AbortSignal): Promise<SearchResult[]> {
  return api<SearchResult[]>(`/api/search?q=${encodeURIComponent(q)}`, { signal });
}
