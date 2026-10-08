import { describe, expect, it, vi } from 'vitest';
import { api, ApiError, getAccessToken, onSessionEnded, setSession } from './client';
import { fakeFetch, json } from '../test/fakeFetch';

const token = (value: string) => ({ accessToken: value, tokenType: 'Bearer', expiresIn: 900 });

describe('api client', () => {
  it('sends the in-memory access token and returns the JSON body', async () => {
    const http = fakeFetch({ 'GET /api/portfolio': () => json(200, { cashCents: 5 }) });
    setSession('t1', 900);

    expect(await api('/api/portfolio')).toEqual({ cashCents: 5 });
    expect(http.calls[0].headers.authorization).toBe('Bearer t1');
  });

  it('refreshes once on 401 and retries with the new token and the same idempotency key', async () => {
    const http = fakeFetch({
      'POST /api/orders': (call) =>
        call.headers.authorization === 'Bearer fresh' ? json(202, { id: 'o1' }) : json(401, { title: 'Unauthorized' }),
      'POST /api/auth/refresh': () => json(200, token('fresh')),
    });
    setSession('stale', 900);

    const order = await api('/api/orders', { method: 'POST', body: { symbol: 'AAPL' }, idempotencyKey: true });

    expect(order).toEqual({ id: 'o1' });
    const attempts = http.calls.filter((c) => c.path === '/api/orders');
    expect(attempts).toHaveLength(2);
    expect(attempts[0].headers['idempotency-key']).toBeTruthy();
    expect(attempts[1].headers['idempotency-key']).toBe(attempts[0].headers['idempotency-key']);
    expect(getAccessToken()).toBe('fresh');
  });

  it('shares one refresh between concurrent requests that hit 401', async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    const http = fakeFetch({
      'GET /api/a': (c) => (c.headers.authorization === 'Bearer fresh' ? json(200, 'a') : json(401)),
      'GET /api/b': (c) => (c.headers.authorization === 'Bearer fresh' ? json(200, 'b') : json(401)),
      'POST /api/auth/refresh': async () => {
        await gate;
        return json(200, token('fresh'));
      },
    });
    setSession('stale', 900);

    const both = Promise.all([api('/api/a'), api('/api/b')]);
    await vi.waitFor(() => expect(http.count('POST /api/auth/refresh')).toBe(1));
    release();

    expect(await both).toEqual(['a', 'b']);
    expect(http.count('POST /api/auth/refresh')).toBe(1);
  });

  it('ends the session when the refresh cookie is no longer valid', async () => {
    fakeFetch({
      'GET /api/portfolio': () => json(401, { title: 'Unauthorized' }),
      'POST /api/auth/refresh': () => json(401, { title: 'Unauthorized' }),
    });
    setSession('stale', 900);
    const ended = vi.fn();
    const unsubscribe = onSessionEnded(ended);

    await expect(api('/api/portfolio')).rejects.toMatchObject({ status: 401 });
    expect(ended).toHaveBeenCalledOnce();
    expect(getAccessToken()).toBeNull();
    unsubscribe();
  });

  it('turns problem documents into ApiError with the server code', async () => {
    fakeFetch({
      'POST /api/orders': () =>
        json(422, { title: 'Unprocessable', code: 'INSUFFICIENT_BUYING_POWER', detail: 'Not enough cash' }),
    });
    setSession('t', 900);

    const error = await api('/api/orders', { method: 'POST', body: {} }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 422, code: 'INSUFFICIENT_BUYING_POWER', message: 'Not enough cash' });
  });

  it('returns undefined for 204', async () => {
    fakeFetch({ 'DELETE /api/watchlists/1/symbols/AAPL': () => new Response(null, { status: 204 }) });
    setSession('t', 900);
    expect(await api('/api/watchlists/1/symbols/AAPL', { method: 'DELETE' })).toBeUndefined();
  });

  it('refreshes the access token a minute before it expires', async () => {
    vi.useFakeTimers();
    const http = fakeFetch({ 'POST /api/auth/refresh': () => json(200, token('next')) });
    setSession('first', 900);

    await vi.advanceTimersByTimeAsync(839_000);
    expect(http.count('POST /api/auth/refresh')).toBe(0);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(http.count('POST /api/auth/refresh')).toBe(1);
    expect(getAccessToken()).toBe('next');
  });
});

describe('refresh across tabs', () => {
  it('holds the cross-tab lock for the whole refresh request', async () => {
    const events: string[] = [];
    vi.stubGlobal('navigator', {
      ...navigator,
      locks: {
        request: async (name: string, task: () => Promise<unknown>) => {
          events.push(`lock ${name}`);
          const result = await task();
          events.push('unlock');
          return result;
        },
      },
    });
    fakeFetch({
      'POST /api/auth/refresh': () => {
        events.push('refresh');
        return json(200, token('t'));
      },
    });
    const { refreshSession } = await import('./client');

    expect(await refreshSession()).toBe(true);
    expect(events).toEqual(['lock tradewind-auth-refresh', 'refresh', 'unlock']);
  });
});
