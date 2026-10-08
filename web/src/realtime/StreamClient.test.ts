import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeSocket } from '../test/FakeSocket';
import { BACKOFF_BASE_MS, SILENCE_TIMEOUT_MS, StreamClient } from './StreamClient';

class SessionEnded extends Error {}

function setup(options: { random?: number; ticket?: () => Promise<string> } = {}) {
  const sockets: FakeSocket[] = [];
  let tickets = 0;
  const fetchTicket = vi.fn(options.ticket ?? (async () => `t${++tickets}`));
  const client = new StreamClient({
    fetchTicket,
    createSocket: (url) => {
      const socket = new FakeSocket(url);
      sockets.push(socket);
      return socket;
    },
    socketUrl: (ticket) => `ws://test/ws?ticket=${ticket}`,
    isSessionEnded: (e) => e instanceof SessionEnded,
    random: () => options.random ?? 0.999,
  });
  const last = () => sockets[sockets.length - 1];
  return { client, sockets, last, fetchTicket };
}

describe('StreamClient', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  it('connects with a fresh ticket and subscribes once the server says hello', async () => {
    const { client, last } = setup();
    const release = client.subscribe(['AAPL', 'TSLA']);       // before connecting: queued
    client.start();
    await vi.advanceTimersByTimeAsync(0);

    expect(last().url).toBe('ws://test/ws?ticket=t1');
    expect(client.state).toBe('connecting');
    last().hello('stream-a');

    expect(client.state).toBe('open');
    expect(client.instanceId).toBe('stream-a');
    expect(last().ops('subscribe')).toEqual([['AAPL', 'TSLA']]);
    release();
    expect(last().ops('unsubscribe')).toEqual([['AAPL', 'TSLA']]);
  });

  it('reference-counts subscriptions across components', async () => {
    const { client, last } = setup();
    client.start();
    await vi.advanceTimersByTimeAsync(0);
    last().hello();

    const a = client.subscribe(['AAPL']);
    const b = client.subscribe(['AAPL', 'MSFT']);
    expect(last().ops('subscribe')).toEqual([['AAPL'], ['MSFT']]);
    a();
    a();                                     // releasing twice is harmless
    expect(last().ops('unsubscribe')).toEqual([]);
    b();
    expect(last().ops('unsubscribe')).toEqual([['AAPL', 'MSFT']]);
  });

  it('reconnects after a drop with a new ticket, resubscribes and asks the app to refetch', async () => {
    const { client, sockets, last, fetchTicket } = setup();
    const refetch = vi.fn();
    client.onReconnect(refetch);
    client.subscribe(['AAPL']);
    client.start();
    await vi.advanceTimersByTimeAsync(0);
    last().hello('stream-a');

    last().serverClose(1006);              // instance killed
    expect(client.state).toBe('reconnecting');
    await vi.advanceTimersByTimeAsync(BACKOFF_BASE_MS - 2);
    expect(fetchTicket).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);  // first retry within the 250ms jitter window
    expect(fetchTicket).toHaveBeenCalledTimes(2);
    expect(sockets).toHaveLength(2);
    expect(last().url).toBe('ws://test/ws?ticket=t2');

    last().hello('stream-b');
    expect(client.instanceId).toBe('stream-b');
    expect(last().ops('subscribe')).toEqual([['AAPL']]);
    expect(refetch).toHaveBeenCalledOnce();
  });

  it('backs off exponentially with jitter up to 5s while the server is unreachable', async () => {
    const { client, fetchTicket } = setup({ ticket: async () => Promise.reject(new Error('network')) });
    client.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchTicket).toHaveBeenCalledTimes(1);

    // random() = 0.999: delays just under 250, 500, 1000, 2000, 4000, 5000, 5000
    const expected = [249, 499, 999, 1998, 3996, 4995, 4995];
    for (const delay of expected) {
      await vi.advanceTimersByTimeAsync(delay - 1);
      const before = fetchTicket.mock.calls.length;
      await vi.advanceTimersByTimeAsync(1);
      expect(fetchTicket.mock.calls.length).toBe(before + 1);
    }
  });

  it('spreads reconnects: with random() = 0 the first retry is immediate', async () => {
    const { client, last, fetchTicket } = setup({ random: 0 });
    client.start();
    await vi.advanceTimersByTimeAsync(0);
    last().hello();
    last().serverClose();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchTicket).toHaveBeenCalledTimes(2);
  });

  it('stops for good when the session has ended', async () => {
    const { client, fetchTicket } = setup({ ticket: async () => Promise.reject(new SessionEnded()) });
    client.start();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(client.state).toBe('stopped');
    expect(fetchTicket).toHaveBeenCalledTimes(1);
  });

  it('pings every heartbeat and replaces a socket that went silent', async () => {
    const { client, sockets, last } = setup();
    client.start();
    await vi.advanceTimersByTimeAsync(0);
    last().hello('a', 15);

    await vi.advanceTimersByTimeAsync(15_000);
    expect(last().ops('ping')).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(SILENCE_TIMEOUT_MS - 15_000 - 1);
    expect(sockets).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);    // 45s without a single message from the server
    expect(sockets[0].closedByClient).not.toBeNull();
    expect(client.state).toBe('reconnecting');
    await vi.advanceTimersByTimeAsync(BACKOFF_BASE_MS);
    expect(sockets).toHaveLength(2);
  });

  it('keeps the subscribe-time prevClose across live ticks and notifies quote listeners', async () => {
    const { client, last } = setup();
    client.start();
    await vi.advanceTimersByTimeAsync(0);
    last().hello();
    const listener = vi.fn();
    client.onQuote('AAPL', listener);

    last().receive({ type: 'quote', symbol: 'AAPL', bid: '199.9900', ask: '200.0100', last: '200.0000',
      bidSize: 1, askSize: 1, prevClose: '195.0000', ts: '2026-01-05T15:00:00Z' });
    last().receive({ type: 'quote', symbol: 'AAPL', bid: '200.9900', ask: '201.0100', last: '201.0000',
      bidSize: 1, askSize: 1, ts: '2026-01-05T15:00:01Z' });

    expect(client.getQuote('AAPL')).toMatchObject({ last: '201.0000', prevClose: '195.0000' });
    expect(listener).toHaveBeenCalledTimes(2);
  });

  it('forwards order and portfolio events', async () => {
    const { client, last } = setup();
    const events = vi.fn();
    client.onEvent(events);
    client.start();
    await vi.advanceTimersByTimeAsync(0);
    last().hello();
    last().receive({ type: 'order', order: { id: 'o1', status: 'FILLED' } });
    last().receive({ type: 'portfolio', reason: 'FILL', cashCents: 1 });
    last().receive({ type: 'pong', ts: 'x' });
    expect(events.mock.calls.map(([e]) => e.type)).toEqual(['order', 'portfolio']);
  });

  it('stop closes the socket and never reconnects', async () => {
    const { client, sockets, last } = setup();
    client.start();
    await vi.advanceTimersByTimeAsync(0);
    last().hello();
    client.stop();
    expect(sockets[0].closedByClient).toBe(1000);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(sockets).toHaveLength(1);
    expect(client.state).toBe('stopped');
  });
});
