import { api } from '../api/client';

export interface Watchlist {
  id: string;
  name: string;
  symbols: string[];
  createdAt: string;
}

export const DEFAULT_LIST_NAME = 'My First List';

export function listWatchlists(signal?: AbortSignal): Promise<Watchlist[]> {
  return api<Watchlist[]>('/api/watchlists', { signal });
}

export function createWatchlist(name: string, idempotencyKey: string): Promise<Watchlist> {
  return api<Watchlist>('/api/watchlists', { method: 'POST', body: { name }, idempotencyKey });
}

export function addSymbol(listId: string, symbol: string, idempotencyKey: string): Promise<Watchlist> {
  return api<Watchlist>(`/api/watchlists/${listId}/symbols/${encodeURIComponent(symbol)}`,
    { method: 'PUT', idempotencyKey });
}

export function removeSymbol(listId: string, symbol: string, idempotencyKey: string): Promise<Watchlist> {
  return api<Watchlist>(`/api/watchlists/${listId}/symbols/${encodeURIComponent(symbol)}`,
    { method: 'DELETE', idempotencyKey });
}
