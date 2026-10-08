import { createContext, useContext, useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { StreamClient, type ConnectionState, type Quote, type SocketLike, type UserEvent } from './StreamClient';

const RealtimeContext = createContext<StreamClient | null>(null);

export function browserStreamClient(): StreamClient {
  return new StreamClient({
    fetchTicket: async () => (await api<{ ticket: string }>('/api/ws/ticket', { method: 'POST' })).ticket,
    // The browser socket matches SocketLike structurally; only its event parameter types are narrower.
    createSocket: (url) => new WebSocket(url) as unknown as SocketLike,
    socketUrl: (ticket) =>
      `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws?ticket=${encodeURIComponent(ticket)}`,
    isSessionEnded: (error) => error instanceof ApiError && error.status === 401,
  });
}

interface PortfolioCache {
  cashCents: number;
  cashReservedCents: number;
  buyingPowerCents: number;
}

/** Applies a pushed event to the query cache; anything structural is refetched from REST. */
export function applyUserEvent(queryClient: QueryClient, event: UserEvent): void {
  if (event.type === 'order') {
    queryClient.setQueryData(['order', event.order.id], event.order);
    void queryClient.invalidateQueries({ queryKey: ['orders'] });
    return;
  }
  queryClient.setQueryData<PortfolioCache>(['portfolio'], (old) =>
    old && { ...old, cashCents: event.cashCents, cashReservedCents: event.cashReservedCents,
      buyingPowerCents: event.buyingPowerCents });
  if (event.symbol) {
    void queryClient.invalidateQueries({ queryKey: ['portfolio'] });       // a position changed
  }
}

/**
 * Owns the WebSocket for the signed-in session: started on sign-in, stopped on sign-out. Pushed events update the
 * query cache; after a reconnect every query is refetched, since events sent while disconnected are lost.
 */
export function RealtimeProvider({ children, create = browserStreamClient }:
                                 { children: ReactNode; create?: () => StreamClient }) {
  const { status } = useAuth();
  const queryClient = useQueryClient();
  const [client, setClient] = useState<StreamClient | null>(null);

  useEffect(() => {
    if (status !== 'authenticated') {
      return undefined;
    }
    const stream = create();
    const offEvent = stream.onEvent((event) => applyUserEvent(queryClient, event));
    const offReconnect = stream.onReconnect(() => void queryClient.invalidateQueries());
    stream.start();
    setClient(stream);
    return () => {
      offEvent();
      offReconnect();
      stream.stop();
      setClient(null);
    };
  }, [status, queryClient, create]);

  return <RealtimeContext.Provider value={client}>{children}</RealtimeContext.Provider>;
}

export function useStreamClient(): StreamClient | null {
  return useContext(RealtimeContext);
}

const noop = () => () => {};

export function useConnection(): { state: ConnectionState; instanceId: string | null } {
  const client = useStreamClient();
  const state = useSyncExternalStore(client ? (cb) => client.onStateChange(cb) : noop,
    () => client?.state ?? 'idle');
  return { state, instanceId: client?.instanceId ?? null };
}

/** Live quote for one symbol (subscribed while the component is mounted). */
export function useQuote(symbol: string | undefined): Quote | undefined {
  return useQuotes(symbol ? [symbol] : [])[0];
}

/** Live quotes, index-aligned with {@code symbols}; entries are undefined until the first quote arrives. */
export function useQuotes(symbols: string[]): (Quote | undefined)[] {
  const client = useStreamClient();
  const key = symbols.join(',');
  const cache = useRef<(Quote | undefined)[]>([]);

  useEffect(() => {
    if (!client || key === '') {
      return undefined;
    }
    return client.subscribe(key.split(','));
  }, [client, key]);

  return useSyncExternalStore(
    (onChange) => {
      if (!client || key === '') {
        return () => {};
      }
      const offs = key.split(',').map((symbol) => client.onQuote(symbol, onChange));
      return () => offs.forEach((off) => off());
    },
    () => {
      const next = key === '' ? [] : key.split(',').map((symbol) => client?.getQuote(symbol));
      const previous = cache.current;
      if (previous.length === next.length && previous.every((quote, i) => quote === next[i])) {
        return previous;
      }
      cache.current = next;
      return next;
    },
  );
}
