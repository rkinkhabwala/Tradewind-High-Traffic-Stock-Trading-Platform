import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { describe, expect, it } from 'vitest';
import { fakeFetch, json } from '../test/fakeFetch';
import { quote, renderApp, token } from '../test/renderApp';
import { buildDraft } from './TradePanel';

const symbolView = { symbol: 'AAPL', name: 'Apple Inc.', sector: 'Technology', last: '200.0000', prevClose: '195.0000',
  dayChange: '5.00', dayChangePercent: '2.56', ts: null };
const portfolio = { accountId: 'a', cashCents: 1_000_000, cashReservedCents: 0, buyingPowerCents: 1_000_000,
  positions: [{ symbol: 'AAPL', qty: '3.000000', avgCost: '150.0000', last: '200.0000', marketValueCents: 60_000,
    unrealizedPnlCents: 15_000, dayChangeCents: 1_500 }],
  positionsValueCents: 60_000, totalValueCents: 1_060_000, dayChangeCents: 1_500, livePrices: true };
const order = (over: Record<string, unknown> = {}) => ({ id: 'o1', clientOrderId: 'k', symbol: 'AAPL', side: 'BUY',
  type: 'MARKET', timeInForce: 'DAY', qty: '2.000000', limitPrice: null, status: 'ACCEPTED', filledQty: '0.000000',
  avgFillPrice: null, rejectReason: null, cancelRequestedAt: null, createdAt: '2026-01-05T15:00:00Z',
  updatedAt: '2026-01-05T15:00:00Z', ...over });

function backend(orders: (body: unknown, key: string) => Response) {
  return fakeFetch({
    'POST /api/auth/refresh': () => json(200, token),
    'GET /api/symbols/AAPL': () => json(200, symbolView),
    'GET /api/symbols/AAPL/candles': () => json(200, []),
    'GET /api/portfolio': () => json(200, portfolio),
    'GET /api/watchlists': () => json(200, []),
    'POST /api/orders': (c) => orders(c.body, c.headers['idempotency-key']),
  });
}

describe('buildDraft', () => {
  const q = { symbol: 'AAPL', bid: '199.9900', ask: '200.0100', last: '200.0000', bidSize: 1, askSize: 1, ts: 'x' };
  const base = { symbol: 'AAPL', side: 'BUY' as const, type: 'MARKET' as const, mode: 'SHARES' as const,
    amount: '2', limitPrice: '', timeInForce: 'GTC' as const, quote: q };

  it('prices a market buy at the ask and a market sell at the bid', () => {
    expect(buildDraft(base)).toMatchObject({ referencePrice: '200.0100', estimatedCents: 40_002,
      order: { symbol: 'AAPL', side: 'BUY', type: 'MARKET', qty: '2', timeInForce: 'DAY' } });
    expect(buildDraft({ ...base, side: 'SELL' })).toMatchObject({ referencePrice: '199.9900', estimatedCents: 39_998 });
  });

  it('turns dollars into an estimated share count, rounded down', () => {
    expect(buildDraft({ ...base, mode: 'DOLLARS', amount: '100' })).toMatchObject({ estimatedQty: true,
      order: { qty: '0.499975' }, estimatedCents: 10_000 });
    expect(buildDraft({ ...base, type: 'LIMIT', limitPrice: '150', mode: 'DOLLARS', amount: '300' }))
      .toMatchObject({ order: { qty: '2.000000', limitPrice: '150', timeInForce: 'GTC' }, estimatedCents: 30_000 });
  });

  it('explains what is missing', () => {
    expect(buildDraft({ ...base, amount: '0' })).toMatch(/number of shares/);
    expect(buildDraft({ ...base, type: 'LIMIT', limitPrice: '1.005' })).toMatch(/limit price/);
    expect(buildDraft({ ...base, quote: undefined })).toMatch(/Waiting/);
    expect(buildDraft({ ...base, mode: 'DOLLARS', amount: '0.00' })).toMatch(/dollar amount/);
  });
});

describe('TradePanel', () => {
  it('reviews then submits a market buy with one idempotency key, reused when retrying', async () => {
    const keys: string[] = [];
    let fail = true;
    const http = backend((_body, key) => {
      keys.push(key);
      if (fail) {
        fail = false;
        return json(503, { title: 'Temporarily unavailable', detail: 'retry with the same Idempotency-Key' });
      }
      return json(202, order());
    });
    const { socket } = await renderApp('/stocks/AAPL');
    await screen.findByText('Apple Inc.');
    act(() => socket.receive(quote('AAPL', '200.00', { prevClose: '195.0000' })));
    expect(screen.getByTestId('symbol-price')).toHaveTextContent('$200.00');

    const panel = screen.getByRole('form', { name: 'Trade AAPL' });
    await userEvent.type(within(panel).getByLabelText('Shares'), '2');
    expect(within(panel).getByTestId('estimated-total')).toHaveTextContent('$400.02');
    await userEvent.click(within(panel).getByRole('button', { name: 'Review order' }));
    expect(within(panel).getByLabelText('Review order')).toHaveTextContent('Buy 2 AAPL at market price.');

    await userEvent.click(within(panel).getByRole('button', { name: 'Submit buy order' }));
    expect(await within(panel).findByRole('alert')).toBeInTheDocument();
    await userEvent.click(within(panel).getByRole('button', { name: 'Submit buy order' }));

    expect(await screen.findByText('Buy order for 2 AAPL submitted')).toBeInTheDocument();
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
    expect(http.calls.find((c) => c.path === '/api/orders')?.body).toEqual(
      { symbol: 'AAPL', side: 'BUY', type: 'MARKET', qty: '2', timeInForce: 'DAY' });
  });

  it('shows the reason when the server rejects the order', async () => {
    backend(() => json(422, { title: 'Unprocessable', code: 'INSUFFICIENT_BALANCE', detail: 'x', orderId: 'o1' }));
    const { socket } = await renderApp('/stocks/AAPL');
    await screen.findByText('Apple Inc.');
    act(() => socket.receive(quote('AAPL', '200.00')));
    const panel = screen.getByRole('form', { name: 'Trade AAPL' });
    await userEvent.click(within(panel).getByRole('tab', { name: 'Sell AAPL' }));
    expect(within(panel).getByText('3 shares available')).toBeInTheDocument();
    await userEvent.type(within(panel).getByLabelText('Shares'), '5');
    await userEvent.click(within(panel).getByRole('button', { name: 'Review order' }));
    await userEvent.click(within(panel).getByRole('button', { name: 'Submit sell order' }));
    expect(await within(panel).findByRole('alert')).toHaveTextContent('Not enough buying power (or shares)');
  });

  it('pops a toast when the fill is pushed', async () => {
    backend(() => json(202, order()));
    const { socket } = await renderApp('/stocks/AAPL');
    await screen.findByText('Apple Inc.');
    act(() => socket.receive({ type: 'order', order: order({ status: 'FILLED', filledQty: '2.000000',
      avgFillPrice: '200.0100' }) }));
    const toast = await screen.findByText('Bought 2 AAPL at $200.01');
    expect(toast.closest('[data-testid="toast"]')).toHaveAttribute('data-tag', 'order-FILLED-o1');
  });

  it('adds the stock to a new list the first time', async () => {
    const http = fakeFetch({
      'POST /api/auth/refresh': () => json(200, token),
      'GET /api/symbols/AAPL': () => json(200, symbolView),
      'GET /api/symbols/AAPL/candles': () => json(200, []),
      'GET /api/portfolio': () => json(200, portfolio),
      'GET /api/watchlists': () => json(200, []),
      'POST /api/watchlists': (c) => json(201, { id: 'w1', name: (c.body as { name: string }).name, symbols: [], createdAt: 'x' }),
      'PUT /api/watchlists/w1/symbols/AAPL': () => json(200, { id: 'w1', name: 'My First List', symbols: ['AAPL'], createdAt: 'x' }),
    });
    await renderApp('/stocks/AAPL');
    await userEvent.click(await screen.findByRole('button', { name: '☆ Add to list' }));
    await waitFor(() => expect(http.count('PUT /api/watchlists/w1/symbols/AAPL')).toBe(1));
    const create = http.calls.find((c) => c.method === 'POST' && c.path === '/api/watchlists');
    expect(create?.body).toEqual({ name: 'My First List' });
    expect(create?.headers['idempotency-key']).toBeTruthy();
  });
});
