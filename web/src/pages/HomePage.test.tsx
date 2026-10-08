import { QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { createQueryClient, routes } from '../App';
import { AuthProvider } from '../auth/AuthProvider';
import { RealtimeProvider } from '../realtime/RealtimeProvider';
import { StreamClient } from '../realtime/StreamClient';
import { fakeFetch, json } from '../test/fakeFetch';
import { FakeSocket } from '../test/FakeSocket';

const token = { accessToken: 'access', tokenType: 'Bearer', expiresIn: 900 };
const position = { symbol: 'AAPL', qty: '10.000000', avgCost: '190.0000', last: '200.0000', marketValueCents: 200_000,
  unrealizedPnlCents: 10_000, dayChangeCents: 0 };
const portfolio = { accountId: 'a', cashCents: 1_000_000, cashReservedCents: 0, buyingPowerCents: 1_000_000,
  positions: [position], positionsValueCents: 200_000, totalValueCents: 1_200_000, dayChangeCents: 0, livePrices: true };

function renderHome() {
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
  const router = createMemoryRouter(routes, { initialEntries: ['/'] });
  render(
    <QueryClientProvider client={createQueryClient()}>
      <AuthProvider>
        <RealtimeProvider create={create}>
          <RouterProvider router={router} />
        </RealtimeProvider>
      </AuthProvider>
    </QueryClientProvider>,
  );
  return sockets;
}

describe('Home', () => {
  it('revalues the portfolio as quotes stream in and applies pushed balance changes', async () => {
    let restPortfolio = portfolio;
    const http = fakeFetch({
      'POST /api/auth/refresh': () => json(200, token),
      'GET /api/portfolio': () => json(200, restPortfolio),
      'GET /api/portfolio/history': () => json(200, { range: '1D', points: [] }),
    });
    const sockets = renderHome();

    expect(await screen.findByTestId('portfolio-value')).toHaveTextContent('$12,000.00');
    await waitFor(() => expect(sockets).toHaveLength(1));
    const socket = sockets[0];
    act(() => socket.hello('stream-a'));
    await waitFor(() => expect(socket.ops('subscribe')).toEqual([['AAPL']]));
    expect((await screen.findByText('Live')).closest('[data-instance]')).toHaveAttribute('data-instance', 'stream-a');

    act(() => socket.receive({ type: 'quote', symbol: 'AAPL', bid: '209.9900', ask: '210.0100', last: '210.0000',
      bidSize: 1, askSize: 1, prevClose: '205.0000', ts: '2026-01-05T15:00:00Z' }));
    expect(screen.getByTestId('portfolio-value')).toHaveTextContent('$12,100.00');
    expect(screen.getByTestId('portfolio-change')).toHaveTextContent('+$50.00 (+0.41%) Today');
    expect(within(screen.getByTestId('position-AAPL')).getByText('$210.00')).toBeInTheDocument();

    // A reservation is pushed: buying power drops immediately, without a refetch.
    const fetchesBefore = http.count('GET /api/portfolio');
    act(() => socket.receive({ type: 'portfolio', reason: 'RESERVE', cashCents: 900_000, cashReservedCents: 100_000,
      buyingPowerCents: 900_000, symbol: null, positionQty: null, occurredAt: 'x' }));
    await waitFor(() => expect(screen.getByTestId('buying-power')).toHaveTextContent('$9,000.00'));
    expect(http.count('GET /api/portfolio')).toBe(fetchesBefore);

    // A fill changes a position: the portfolio is refetched from REST.
    restPortfolio = { ...portfolio, cashCents: 900_000, buyingPowerCents: 900_000,
      positions: [{ ...position, qty: '15.000000' }] };
    act(() => socket.receive({ type: 'portfolio', reason: 'FILL', cashCents: 900_000, cashReservedCents: 0,
      buyingPowerCents: 900_000, symbol: 'AAPL', positionQty: '15.000000', occurredAt: 'x' }));
    expect(await within(screen.getByTestId('position-AAPL')).findByText('15 shares')).toBeInTheDocument();
    // 900,000 + 15 x 210.00
    expect(screen.getByTestId('portfolio-value')).toHaveTextContent('$12,150.00');
  });

  it('refetches everything after reconnecting to another instance', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0);
    const http = fakeFetch({
      'POST /api/auth/refresh': () => json(200, token),
      'GET /api/portfolio': () => json(200, portfolio),
      'GET /api/portfolio/history': () => json(200, { range: '1D', points: [] }),
    });
    const sockets = renderHome();
    await screen.findByTestId('portfolio-value');
    await waitFor(() => expect(sockets).toHaveLength(1));
    act(() => sockets[0].hello('stream-a'));
    const before = http.count('GET /api/portfolio');

    act(() => sockets[0].serverClose(1006));
    expect(await screen.findByText('Reconnecting…')).toBeInTheDocument();
    await waitFor(() => expect(sockets).toHaveLength(2));
    act(() => sockets[1].hello('stream-b'));

    expect(await screen.findByText('Live')).toBeInTheDocument();
    await waitFor(() => expect(http.count('GET /api/portfolio')).toBeGreaterThan(before));
    expect(sockets[1].ops('subscribe')).toEqual([['AAPL']]);
  });
});
