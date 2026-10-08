import { QueryClientProvider } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { expect } from 'vitest';
import { createQueryClient, routes } from '../App';
import { AuthProvider } from '../auth/AuthProvider';
import { RealtimeProvider } from '../realtime/RealtimeProvider';
import { StreamClient } from '../realtime/StreamClient';
import { OrderToasts } from '../toast/OrderToasts';
import { ToastProvider } from '../toast/ToastProvider';
import { FakeSocket } from './FakeSocket';

/** The whole app at {@code path}, signed in, with a fake WebSocket the test drives. Returns once it said hello. */
export async function renderApp(path: string) {
  const sockets: FakeSocket[] = [];
  const create = () => new StreamClient({
    fetchTicket: async () => 'ticket',
    createSocket: (url) => {
      const s = new FakeSocket(url);
      sockets.push(s);
      return s;
    },
    socketUrl: (t) => `ws://test/ws?ticket=${t}`,
    isSessionEnded: () => false,
  });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={createQueryClient()}>
      <AuthProvider>
        <RealtimeProvider create={create}>
          <ToastProvider>
            <OrderToasts />
            <RouterProvider router={router} />
          </ToastProvider>
        </RealtimeProvider>
      </AuthProvider>
    </QueryClientProvider>,
  );
  await waitFor(() => expect(sockets).toHaveLength(1));
  const socket = sockets[0];
  act(() => socket.hello());
  return { socket, router };
}

export const token = { accessToken: 'access', tokenType: 'Bearer', expiresIn: 900 };

export function quote(symbol: string, last: string, extra: Record<string, unknown> = {}) {
  const cents = Math.round(Number(last) * 100);
  const fmt = (c: number) => `${Math.trunc(c / 100)}.${String(c % 100).padStart(2, '0')}00`;
  return { type: 'quote', symbol, bid: fmt(cents - 1), ask: fmt(cents + 1), last: fmt(cents), bidSize: 100,
    askSize: 100, ts: '2026-01-05T15:00:00Z', ...extra };
}
