import { expect, test } from '@playwright/test';
import { compose, docker, expectLive, healthyStreamInstances, instanceOf, loadUser, login, percentile, recordQuotes,
  report } from './support';

/**
 * Spec section 9, Phase 2 acceptance, against the running stack with 3 stream-service instances:
 *  1. two browser tabs on different stream-service instances both receive the same quotes;
 *  2. a fill appears on screen < 1s after the order (20 market orders: p50/p95/max reported, pass iff p95 < 1s);
 *  3. killing one stream-service instance makes its clients reconnect to another within 5s (actual time reported).
 */
test.describe.configure({ mode: 'serial' });

test('two tabs on different stream-service instances receive the same quotes', async ({ context }) => {
  const user = loadUser();
  const tab1 = await context.newPage();
  const quotes1 = recordQuotes(tab1);
  await login(tab1, user);
  const instance1 = await instanceOf(tab1);

  // Same browser, second tab (shares the session cookie). Round-robin normally lands it elsewhere; retry if not.
  let tab2 = await context.newPage();
  let quotes2 = recordQuotes(tab2);
  await tab2.goto('/');
  await expectLive(tab2);
  for (let attempt = 0; attempt < 5 && (await instanceOf(tab2)) === instance1; attempt++) {
    await tab2.close();
    tab2 = await context.newPage();
    quotes2 = recordQuotes(tab2);
    await tab2.goto('/');
    await expectLive(tab2);
  }
  const instance2 = await instanceOf(tab2);
  expect(instance2, 'tabs must be served by different instances').not.toBe(instance1);

  await Promise.all([tab1.goto('/stocks/AAPL'), tab2.goto('/stocks/AAPL')]);
  await Promise.all([expectLive(tab1), expectLive(tab2)]);
  quotes1.length = 0;
  quotes2.length = 0;
  await tab1.waitForTimeout(5_000);

  const key = (q: { symbol: string; ts: string; last: string }) => `${q.symbol}|${q.ts}|${q.last}`;
  const aapl1 = quotes1.filter((q) => q.symbol === 'AAPL');
  const aapl2 = quotes2.filter((q) => q.symbol === 'AAPL');
  const seen2 = new Set(aapl2.map(key));
  const shared = aapl1.filter((q) => seen2.has(key(q)));
  // Each instance conflates independently (≤ 1 per 250ms per session), so the sets overlap rather than match exactly.
  expect(aapl1.length).toBeGreaterThanOrEqual(5);
  expect(aapl2.length).toBeGreaterThanOrEqual(5);
  expect(shared.length).toBeGreaterThanOrEqual(3);
  // And both screens converge on the same latest price.
  await expect.poll(async () => (await tab1.getByTestId('symbol-price').textContent())
    === (await tab2.getByTestId('symbol-price').textContent()), { timeout: 10_000 }).toBe(true);

  report('sameQuotesOnTwoInstances', { instance1, instance2, quotesTab1: aapl1.length, quotesTab2: aapl2.length,
    identicalQuotes: shared.length, windowSeconds: 5, pass: true });
});

test('a market order fill appears on screen within 1s (20 orders, p95 < 1s)', async ({ page }) => {
  const user = loadUser();
  await login(page, user);
  await page.goto('/stocks/AAPL');
  await expect(page.getByTestId('symbol-price')).not.toHaveText('—');
  const panel = page.getByRole('form', { name: 'Trade AAPL' });
  await expect(panel.getByText('Market price (ask)')).toBeVisible();

  // Timed inside the page: from the click on Submit to the fill toast entering the DOM. A MutationObserver
  // installed up front records when each FILLED toast appears, so no Playwright round trip is counted.
  await page.evaluate(() => {
    const w = window as unknown as { __fills: Record<string, number> };
    w.__fills = {};
    new MutationObserver(() => {
      document.querySelectorAll<HTMLElement>('[data-testid="toast"][data-tag^="order-FILLED-"]').forEach((el) => {
        w.__fills[el.dataset.tag!] ??= performance.now();
      });
    }).observe(document.body, { childList: true, subtree: true });
  });

  const timings: number[] = [];
  for (let i = 0; i < 20; i++) {
    await panel.getByLabel('Shares').fill('1');
    await panel.getByRole('button', { name: 'Review order' }).click();
    const submit = panel.getByRole('button', { name: 'Submit buy order' });
    await submit.evaluate((button) => button.addEventListener('click', () => {
      (window as unknown as { __clicked: number }).__clicked = performance.now();
    }, { once: true }));
    const placed = page.waitForResponse((r) => r.url().endsWith('/api/orders') && r.request().method() === 'POST');
    await submit.click();
    const response = await placed;
    expect(response.status(), await response.text()).toBe(202);
    const { id } = (await response.json()) as { id: string };
    await expect(page.locator(`[data-testid="toast"][data-tag="order-FILLED-${id}"]`)).toBeAttached();
    const ms = await page.evaluate((tag) => {
      const w = window as unknown as { __fills: Record<string, number>; __clicked: number };
      return w.__fills[tag] - w.__clicked;
    }, `order-FILLED-${id}`);
    timings.push(Math.round(ms));
  }

  const sorted = [...timings].sort((a, b) => a - b);
  const p50 = percentile(sorted, 50);
  const p95 = percentile(sorted, 95);
  const max = sorted[sorted.length - 1];
  report('fillOnScreen', { orders: timings.length, p50Ms: p50, p95Ms: p95, maxMs: max, timingsMs: timings,
    pass: p95 < 1000 });
  await page.getByRole('link', { name: 'Home' }).click();
  await expect(page.getByTestId('position-AAPL')).toContainText('20 shares');
  expect(p95, `p95 ${p95}ms (p50 ${p50}ms, max ${max}ms)`).toBeLessThan(1000);
});

test('killing a stream-service instance: clients reconnect to another within 5s', async ({ page }) => {
  const user = loadUser();
  await login(page, user);
  const before = await instanceOf(page);
  const value = page.getByTestId('portfolio-value');
  await expect(value).not.toHaveText('—');

  // Record every state change in the page, so even a reconnect faster than `docker kill` returns is seen.
  await page.evaluate(() => {
    const w = window as unknown as { __states: { t: number; state: string; instance: string | null }[] };
    w.__states = [];
    const badge = document.querySelector<HTMLElement>('[data-state]')!;
    new MutationObserver(() => w.__states.push({ t: Date.now(), state: badge.dataset.state!,
      instance: badge.dataset.instance ?? null })).observe(badge, { attributes: true });
  });
  const killedAt = Date.now();
  docker('kill', before);
  const killReturnedMs = Date.now() - killedAt;
  await expect.poll(async () => {
    const badge = page.locator('[data-state]');
    return (await badge.getAttribute('data-state')) === 'open' && (await badge.getAttribute('data-instance')) !== before;
  }, { timeout: 15_000, intervals: [25] }).toBe(true);
  const states = await page.evaluate(() =>
    (window as unknown as { __states: { t: number; state: string; instance: string | null }[] }).__states);
  const reopened = states.find((s) => s.state === 'open' && s.instance !== before);
  const lost = states.find((s) => s.state !== 'open');
  const reconnectMs = reopened!.t - killedAt;
  const after = await instanceOf(page);

  // Still live: the page keeps receiving prices on the new instance.
  const quotes = recordQuotes(page);
  await page.goto('/stocks/AAPL');
  await expectLive(page);
  await expect.poll(() => quotes.length, { timeout: 5_000 }).toBeGreaterThan(0);

  report('reconnectAfterKill', { killed: before, reconnectedTo: after, dockerKillReturnedMs: killReturnedMs,
    disconnectNoticedMs: lost ? lost.t - killedAt : null, reconnectMs, pass: reconnectMs < 5000 });
  expect(reconnectMs, `reconnected after ${reconnectMs}ms`).toBeLessThan(5000);
});

test.afterAll(() => {
  // Bring the killed instance back so the stack is left as it was found.
  compose('up', '-d', '--scale', 'stream-service=3', 'stream-service');
  const deadline = Date.now() + 90_000;
  while (healthyStreamInstances() < 3 && Date.now() < deadline) {
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 2_000);
  }
});
