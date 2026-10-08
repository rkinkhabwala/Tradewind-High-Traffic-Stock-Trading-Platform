import { api } from '../api/client';
import type { OrderView } from '../realtime/StreamClient';

export type { OrderView };
export type OrderStatus = OrderView['status'];

export interface PlaceOrder {
  symbol: string;
  side: 'BUY' | 'SELL';
  type: 'MARKET' | 'LIMIT';
  qty: string;
  limitPrice?: string;
  timeInForce?: 'DAY' | 'GTC';
}

export interface OrderPage {
  items: OrderView[];
  nextCursor: string | null;
}

export const OPEN_STATUSES: OrderStatus[] = ['PENDING', 'ACCEPTED', 'PARTIALLY_FILLED'];

/** The key identifies the user's intent: retrying the same submit reuses it, so an order is placed at most once. */
export function placeOrder(order: PlaceOrder, idempotencyKey: string): Promise<OrderView> {
  return api<OrderView>('/api/orders', { method: 'POST', body: order, idempotencyKey });
}

export function cancelOrder(id: string, idempotencyKey: string): Promise<OrderView> {
  return api<OrderView>(`/api/orders/${encodeURIComponent(id)}`, { method: 'DELETE', idempotencyKey });
}

export function listOrders(params: { status?: OrderStatus; cursor?: string | null; limit?: number },
                           signal?: AbortSignal): Promise<OrderPage> {
  const query = new URLSearchParams({ limit: String(params.limit ?? 50) });
  if (params.status) {
    query.set('status', params.status);
  }
  if (params.cursor) {
    query.set('cursor', params.cursor);
  }
  return api<OrderPage>(`/api/orders?${query}`, { signal });
}
