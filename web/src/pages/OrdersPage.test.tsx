import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { describe, expect, it } from 'vitest';
import { fakeFetch, json } from '../test/fakeFetch';
import { renderApp, token } from '../test/renderApp';

const order = (id: string, over: Record<string, unknown> = {}) => ({ id, clientOrderId: id, symbol: 'AAPL', side: 'BUY',
  type: 'LIMIT', timeInForce: 'GTC', qty: '5.000000', limitPrice: '150.0000', status: 'ACCEPTED', filledQty: '0.000000',
  avgFillPrice: null, rejectReason: null, cancelRequestedAt: null, createdAt: '2026-01-05T15:00:00Z',
  updatedAt: '2026-01-05T15:00:00Z', ...over });

describe('Orders', () => {
  it('lists orders page by page, cancels an open one, and follows pushed status changes', async () => {
    let first = [order('o1'), order('o2', { status: 'FILLED', type: 'MARKET', limitPrice: null, filledQty: '5.000000',
      avgFillPrice: '149.9900' })];
    const http = fakeFetch({
      'POST /api/auth/refresh': () => json(200, token),
      'GET /api/orders': () => json(200, { items: first, nextCursor: 'c1' }),
      'DELETE /api/orders/o1': () => json(202, order('o1', { cancelRequestedAt: '2026-01-05T15:01:00Z' })),
    });
    const { socket } = await renderApp('/orders');

    const open = await screen.findByTestId('order-o1');
    expect(within(open).getByText('Open')).toBeInTheDocument();
    expect(within(screen.getByTestId('order-o2')).getByText('$149.99')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Load more' })).toBeInTheDocument();

    await userEvent.click(within(open).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(http.count('DELETE /api/orders/o1')).toBe(1));
    expect(http.calls.find((c) => c.method === 'DELETE')?.headers['idempotency-key']).toBeTruthy();
    expect(await screen.findByText('Cancel requested for AAPL')).toBeInTheDocument();

    // The cancel lands: stream-service pushes the new status, the list refetches.
    first = [order('o1', { status: 'CANCELED', rejectReason: 'USER_REQUESTED' }), first[1]];
    act(() => socket.receive({ type: 'order', order: first[0] }));
    expect(await within(await screen.findByTestId('order-o1')).findByText('Canceled')).toBeInTheDocument();
    expect(await screen.findByText('AAPL order canceled.')).toBeInTheDocument();
  });
});
